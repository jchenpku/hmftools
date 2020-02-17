package com.hartwig.hmftools.svtools.rna_expression;

import static java.lang.Math.min;

import static com.hartwig.hmftools.common.utils.io.FileWriterUtils.closeBufferedWriter;
import static com.hartwig.hmftools.common.utils.io.FileWriterUtils.createBufferedWriter;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_END;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_START;
import static com.hartwig.hmftools.sig_analyser.common.DataUtils.convertToPercentages;
import static com.hartwig.hmftools.sig_analyser.common.DataUtils.sumVector;
import static com.hartwig.hmftools.svtools.rna_expression.GeneReadData.TC_LONG;
import static com.hartwig.hmftools.svtools.rna_expression.GeneReadData.TC_SHORT;
import static com.hartwig.hmftools.svtools.rna_expression.GeneReadData.TC_SPLICED;
import static com.hartwig.hmftools.svtools.rna_expression.GeneReadData.TC_UNSPLICED;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hartwig.hmftools.common.variant.structural.annotation.ExonData;
import com.hartwig.hmftools.common.variant.structural.annotation.TranscriptData;
import com.hartwig.hmftools.sig_analyser.common.SigMatrix;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExpectedExpressionRates
{
    private final RnaExpConfig mConfig;

    private final Map<String,List<TranscriptComboData>> mTransComboData;

    private final List<String> mCategories; // equivalent of buckets
    private final List<String> mTranscriptIds; // equivalent of signature names
    private SigMatrix mDefinitionsData;
    private BufferedWriter mWriter;

    public static final String UNSPLICED_ID = "UNSPLICED";

    private static final Logger LOGGER = LogManager.getLogger(ExpectedExpressionRates.class);

    public ExpectedExpressionRates(final RnaExpConfig config)
    {
        mConfig = config;

        mTransComboData = Maps.newHashMap();
        mCategories = Lists.newArrayList();
        mTranscriptIds = Lists.newArrayList();
        mDefinitionsData = null;
        mWriter = null;
    }

    public Map<String,List<TranscriptComboData>> getTransComboData() { return mTransComboData; }

    public List<String> getTranscriptNames() { return mTranscriptIds; }
    public SigMatrix getTranscriptDefinitions() { return mDefinitionsData; }

    public void generateExpectedRates(final GeneReadData geneReadData)
    {
        clearState();

        final List<long[]> commonExonicRegions = geneReadData.getCommonExonicRegions();

        if(commonExonicRegions.size() < 2)
            return;

        // process each transcript as though it were transcribed
        final List<TranscriptData> transDataList = geneReadData.getTranscripts();

        int fragmentLength = mConfig.MinFragmentLength;

        for(TranscriptData transData : geneReadData.getTranscripts())
        {
            boolean endOfTrans = false;

            for(ExonData exon : transData.exons())
            {
                for(long startPos = exon.ExonStart; startPos <= exon.ExonEnd; ++startPos)
                {
                    // if(startPos + )
                    if (!allocateTranscriptCounts(transData, transDataList, startPos))
                    {
                        endOfTrans = true;
                        break;
                    }
                }

                if(endOfTrans)
                    break;
            }
        }

        // and generate fragments assuming an unspliced gene
        long regionStart = geneReadData.getTranscriptsRange()[SE_START];
        long regionEnd = geneReadData.getTranscriptsRange()[SE_END];

        int exonicRegionIndex = 0;
        long currentExonicEnd = commonExonicRegions.get(exonicRegionIndex)[SE_END];
        long nextExonicStart = commonExonicRegions.get(exonicRegionIndex + 1)[SE_START];

        List<String> emptyTrans = Lists.newArrayList();

        for(long startPos = regionStart; startPos <= regionEnd - fragmentLength; ++startPos)
        {
            if(startPos <= currentExonicEnd)
            {
                // check possible transcript exonic matches
                allocateUnsplicedCounts(transDataList, startPos);
            }
            else
            {
                // check for purely intronic fragments
                if(startPos < nextExonicStart)
                {
                    addTransComboData(UNSPLICED_ID, emptyTrans, TC_UNSPLICED);
                }
                else
                {
                    ++exonicRegionIndex;
                    currentExonicEnd = commonExonicRegions.get(exonicRegionIndex)[SE_END];

                    if (exonicRegionIndex < commonExonicRegions.size() - 1)
                    {
                        nextExonicStart = commonExonicRegions.get(exonicRegionIndex + 1)[SE_START];
                    }
                    else
                    {
                        nextExonicStart = -1;
                    }
                }
            }
        }

        formTranscriptDefinitions(geneReadData);
    }

    private boolean allocateTranscriptCounts(final TranscriptData transData, final List<TranscriptData> transDataList, long startPos)
    {
        List<long[]> readRegions = Lists.newArrayList();

        int matchType = generateImpliedFragment(transData, startPos, readRegions);

        if(readRegions.isEmpty())
            return false;

        final List<String> longAndSplicedTrans = Lists.newArrayList();
        final List<String> shortTrans = Lists.newArrayList();

        if(matchType == TC_SPLICED || matchType == TC_LONG)
            longAndSplicedTrans.add(transData.TransName);
        else
            shortTrans.add(transData.TransName);

        // now check whether these regions are supported by each other transcript
        for(TranscriptData otherTransData : transDataList)
        {
            if(transData == otherTransData)
                continue;

            if(readsSupportFragment(otherTransData, readRegions, matchType))
            {
                if(matchType == TC_SPLICED || matchType == TC_LONG)
                    longAndSplicedTrans.add(otherTransData.TransName);
                else
                    shortTrans.add(otherTransData.TransName);
            }
        }

        if(!longAndSplicedTrans.isEmpty())
        {
            addTransComboData(transData.TransName, longAndSplicedTrans, TC_SPLICED);
        }
        else
        {
            addTransComboData(transData.TransName, shortTrans, TC_SHORT);
        }

        return true;
    }

    private void allocateUnsplicedCounts(final List<TranscriptData> transDataList, long startPos)
    {
        List<long[]> readRegions = Lists.newArrayList();

        // the unspliced case
        int fragmentLength = mConfig.MinFragmentLength;
        int readLength = mConfig.ReadLength;

        readRegions.add(new long[] {startPos, startPos + readLength - 1});

        long secondReadEnd = startPos + fragmentLength - 1;
        readRegions.add(new long[] {secondReadEnd - readLength + 1, secondReadEnd});

        final List<String> shortTrans = Lists.newArrayList();

        // check whether these unspliced reads support exonic regions
        for(TranscriptData transData : transDataList)
        {
            if(readsSupportFragment(transData, readRegions, TC_SHORT))
            {
                shortTrans.add(transData.TransName);
            }
        }

        addTransComboData(UNSPLICED_ID, shortTrans, !shortTrans.isEmpty() ? TC_SHORT : TC_UNSPLICED);
    }

    private void addTransComboData(final String transId, final List<String> transcripts, int transMatchType)
    {
        List<TranscriptComboData> transComboDataList = mTransComboData.get(transId);

        if(transComboDataList == null)
        {
            transComboDataList = Lists.newArrayList();
            mTransComboData.put(transId, transComboDataList);
        }

        TranscriptComboData matchingCounts = transComboDataList.stream()
                .filter(x -> x.matches(transcripts)).findFirst().orElse(null);

        if(matchingCounts == null)
        {
            matchingCounts = new TranscriptComboData(transcripts);
            transComboDataList.add(matchingCounts);
        }

        ++matchingCounts.getCounts()[transMatchType];
    }

    public int generateImpliedFragment(final TranscriptData transData, long startPos, List<long[]> readRegions)
    {
        readRegions.clear();

        // set out the fragment reads either within a single exon or spanning one or more
        int fragmentLength = mConfig.MinFragmentLength;

        int exonCount = transData.exons().size();
        final ExonData lastExon = transData.exons().get(exonCount - 1);

        if(startPos + fragmentLength - 1 > lastExon.ExonEnd)
            return TC_UNSPLICED;

        int matchType = TC_SHORT;
        int readLength = mConfig.ReadLength;

        int remainingReadBases = readLength;
        int remainingInterimBases = fragmentLength - 2 * readLength + 1;
        long nextRegionStart = startPos;
        int readsAdded = 0;

        for(int i = 0; i < exonCount; ++i)
        {
            final ExonData exon = transData.exons().get(i);

            if(nextRegionStart > exon.ExonEnd)
                continue;

            if(!readRegions.isEmpty())
            {
                if(matchType != TC_SPLICED)
                    matchType = TC_LONG;
            }

            if(readsAdded == 1 && remainingInterimBases > 0)
            {
                if(nextRegionStart + remainingInterimBases - 1 >= exon.ExonEnd)
                {
                    if(i >= exonCount - 1)
                    {
                        readRegions.clear();
                        return TC_UNSPLICED;
                    }

                    nextRegionStart = transData.exons().get(i + 1).ExonStart;

                    remainingInterimBases -= exon.ExonEnd - exon.ExonStart + 1;
                    continue;

                }

                nextRegionStart += remainingInterimBases;
                remainingInterimBases = 0;
            }

            long regionEnd = min(nextRegionStart + remainingReadBases - 1, exon.ExonEnd);
            long regionLength = (int)(regionEnd - nextRegionStart + 1);
            remainingReadBases -= regionLength;
            readRegions.add(new long[] {nextRegionStart, regionEnd});

            if(remainingReadBases > 0 && regionEnd == exon.ExonEnd)
                matchType = TC_SPLICED;

            if(remainingReadBases == 0)
            {
                ++readsAdded;

                if (readsAdded == 2)
                    break;

                remainingReadBases = readLength;
            }

            // is the remainder of this exon long enough to match again?
            nextRegionStart = regionEnd + remainingInterimBases;

            if(regionEnd == exon.ExonEnd || nextRegionStart > exon.ExonEnd)
            {
                if(i == exonCount - 1)
                {
                    readRegions.clear();
                    return TC_UNSPLICED;
                }

                // will move onto the next exon for further matching
                nextRegionStart = transData.exons().get(i + 1).ExonStart;

                remainingInterimBases -= exon.ExonEnd - regionEnd;
                continue;
            }
            else
            {
                remainingInterimBases = 0;
            }

            // start the next match within this same exon
            regionEnd = min(nextRegionStart + remainingReadBases - 1, exon.ExonEnd);
            regionLength = (int)(regionEnd - nextRegionStart + 1);
            remainingReadBases -= regionLength;
            readRegions.add(new long[] {nextRegionStart, regionEnd});

            if(remainingReadBases > 0 && regionEnd == exon.ExonEnd)
                matchType = TC_SPLICED;

            if(remainingReadBases == 0)
                break;

            if(i == exonCount - 1)
            {
                readRegions.clear();
                return TC_UNSPLICED;
            }

            // will move onto the next exon for further matching
            nextRegionStart = transData.exons().get(i + 1).ExonStart;
        }

        return matchType;
    }

    public boolean readsSupportFragment(final TranscriptData transData, List<long[]> readRegions, int requiredMatchType)
    {
        long regionsStart = readRegions.get(0)[SE_START];
        long regionsEnd = readRegions.get(readRegions.size() - 1)[SE_END];

        if(requiredMatchType == TC_SHORT)
        {
            return (transData.exons().stream().anyMatch(x -> x.ExonStart <= regionsStart && x.ExonEnd >= regionsEnd));
        }
        else
        {
            // none of the reads can breach an exon boundary or skip an exon
            int readIndex = 0;
            long regionStart = readRegions.get(readIndex)[SE_START];
            long regionEnd = readRegions.get(readIndex)[SE_END];
            int exonsMatched = 0;
            boolean atExonEnd = false;
            boolean spliceMatched = false;

            for(int i = 0; i < transData.exons().size(); ++i)
            {
                ExonData exon = transData.exons().get(i);

                // region before the next exon even starts
                if(regionEnd < exon.ExonStart)
                    return false;

                // invalid if overlaps but not fully contained
                if(regionsStart >= exon.ExonStart && regionsStart <= exon.ExonEnd && regionEnd > exon.ExonEnd)
                    return false;
                else if(regionsEnd >= exon.ExonStart && regionsEnd <= exon.ExonEnd && regionStart < exon.ExonStart)
                    return false;

                ++exonsMatched;

                if (requiredMatchType == TC_SPLICED && !spliceMatched && atExonEnd)
                {
                    if(regionStart == exon.ExonStart)
                        spliceMatched = true;
                    else
                        return false;

                    atExonEnd = false;
                }

                while(true)
                {
                    if (regionStart >= exon.ExonStart && regionEnd <= exon.ExonEnd)
                    {
                        if(requiredMatchType == TC_SPLICED && !spliceMatched)
                        {
                            if (regionEnd == exon.ExonEnd)
                                atExonEnd = true;
                        }

                        ++readIndex;

                        if(readIndex >= readRegions.size())
                            break;

                        regionStart = readRegions.get(readIndex)[SE_START];
                        regionEnd = readRegions.get(readIndex)[SE_END];
                    }
                    else
                    {
                        // next region may match the next exon
                        break;
                    }
                }

                if(readIndex >= readRegions.size())
                    break;
            }

            if(requiredMatchType == TC_SPLICED)
                return exonsMatched > 1 && spliceMatched;
            else
                return exonsMatched > 1;
        }
    }

    private void formTranscriptDefinitions(final GeneReadData geneReadData)
    {
        collectCategories();

        int categoryCount = mCategories.size();
        int definitionsCount = mTransComboData.size();

        mDefinitionsData = new SigMatrix(categoryCount, definitionsCount);

        for(Map.Entry<String,List<TranscriptComboData>> entry : mTransComboData.entrySet())
        {
            final String transId = entry.getKey();

            int definitionIndex = mTranscriptIds.size();
            mTranscriptIds.add(transId);

            double[] categoryCounts = new double[categoryCount];

            for(TranscriptComboData tcData : entry.getValue())
            {
                final String transKey = !tcData.getTranscripts().isEmpty() ? tcData.getTranscriptsKey() : UNSPLICED_ID;

                final int[] counts = tcData.getCounts();

                for(int i = 0; i < counts.length; ++i)
                {
                    if(counts[i] > 0)
                    {
                        final String categoryStr = formCategory(transKey, i);
                        int categoryId = getCategoryIndex(categoryStr);

                        if(categoryId < 0)
                        {
                            LOGGER.error("invalid category index from transKey({})", transKey);
                            return;
                        }

                        categoryCounts[categoryId] = counts[i];
                    }
                }
            }

            // convert counts to ratios and add against this transcript definition
            convertToPercentages(categoryCounts);
            mDefinitionsData.setCol(definitionIndex, categoryCounts);
        }

        if(mConfig.WriteExpectedRates)
            writeExpectedRates(geneReadData);
    }

    public double[] generateTranscriptCounts(final GeneReadData geneReadData, final List<TranscriptComboData> transComboData, int unsplicedCounts)
    {
        double[] categoryCounts = new double[mCategories.size()];

        int categoryId = getCategoryIndex(UNSPLICED_ID);

        if(categoryId < 0)
        {
            LOGGER.error("missing unspliced category");
            return categoryCounts;
        }

        int skippedComboCounts = 0;

        categoryCounts[categoryId] = unsplicedCounts;

        for(TranscriptComboData tcData : transComboData)
        {
            final String transKey = !tcData.getTranscripts().isEmpty() ? tcData.getTranscriptsKey() : UNSPLICED_ID;

            final int[] counts = tcData.getCounts();

            if(counts[TC_SHORT] > 0)
            {
                final String categoryStr = formCategory(transKey, TC_SHORT);
                categoryId = getCategoryIndex(categoryStr);

                // for now if a category isn't found just log and then ignore the count in it
                if(categoryId < 0)
                {
                    LOGGER.warn("category({}) skipped with count({})", categoryStr, counts[TC_SHORT]);
                    skippedComboCounts += counts[TC_SHORT];
                }
                else
                {
                    categoryCounts[categoryId] = counts[TC_SHORT];
                }
            }

            int multiExonCount = counts[TC_SPLICED] + counts[TC_LONG];
            if(multiExonCount > 0)
            {
                final String categoryStr = formCategory(transKey, TC_SPLICED);
                categoryId = getCategoryIndex(categoryStr);

                if(categoryId < 0)
                {
                    LOGGER.warn("category({}) skipped with count({})", categoryStr, multiExonCount);
                    skippedComboCounts += counts[TC_SHORT];
                }
                else
                {
                    categoryCounts[categoryId] = multiExonCount;
                }
            }
        }

        if(skippedComboCounts > 0)
        {
            double totalCounts = sumVector(categoryCounts) + skippedComboCounts;

            LOGGER.info(String.format("gene(%s) skippedCounts(%d perc=%.3f of total=%.0f)",
                    geneReadData.GeneData.GeneName, skippedComboCounts, skippedComboCounts/totalCounts, totalCounts));
        }

        return categoryCounts;
    }

    private int getCategoryIndex(final String category)
    {
        for(int i = 0; i < mCategories.size(); ++i)
        {
            if(mCategories.get(i).equals(category))
                return i;
        }

        return -1;
    }

    private void addCategory(final String category)
    {
        if(!mCategories.contains(category))
            mCategories.add(category);
    }

    private static final String UNSPLICED_STR = "UNSPLC";

    private void collectCategories()
    {
        addCategory(UNSPLICED_ID);

        for(Map.Entry<String,List<TranscriptComboData>> entry : mTransComboData.entrySet())
        {
            for(TranscriptComboData tcData : entry.getValue())
            {
                boolean hasTrans = !tcData.getTranscripts().isEmpty();
                final String transKey = hasTrans ? tcData.getTranscriptsKey() : UNSPLICED_ID;

                if(tcData.getCount(TC_SHORT) > 0)
                {
                    addCategory(formCategory(transKey, TC_SHORT));
                }

                if(tcData.getCount(TC_SPLICED) > 0)
                {
                    addCategory(transKey);
                }
            }
        }
    }

    private static String formCategory(final String transKey, int countsType)
    {
        if(countsType == TC_SHORT)
            return String.format("%s-%s", transKey, UNSPLICED_STR);
        else if(countsType == TC_SPLICED)
            return transKey;
        else
            return UNSPLICED_ID;
    }

    private void clearState()
    {
        mTransComboData.clear();
        mDefinitionsData = null;
        mCategories.clear();
        mTranscriptIds.clear();
    }

    private void writeExpectedRates(final GeneReadData geneReadData)
    {
        if(mConfig.OutputDir.isEmpty())
            return;

        if(mDefinitionsData == null || mCategories.isEmpty() || mTranscriptIds.isEmpty())
            return;

        try
        {
            if(mWriter == null)
            {
                final String outputFileName = mConfig.OutputDir + "RNA_EXP_EXP_RATES.csv";

                mWriter = createBufferedWriter(outputFileName, false);
                mWriter.write("GeneId,GeneName,Transcript,Category,Rate");
                mWriter.newLine();
            }

            final String geneId = geneReadData.GeneData.GeneId;
            final String geneName = geneReadData.GeneData.GeneName;

            for(int i = 0; i < mDefinitionsData.Cols; ++i)
            {
                final String transcriptId = mTranscriptIds.get(i);

                for(int j = 0; j < mDefinitionsData.Rows; ++j)
                {
                    final String category = mCategories.get(j);

                    double expRate = mDefinitionsData.get(j, i);

                    if(expRate == 0)
                        continue;

                    mWriter.write(String.format("%s,%s,%s,%s,%.4f",
                            geneId, geneName, transcriptId, category, expRate));
                    mWriter.newLine();
                }
            }
        }
        catch(IOException e)
        {
            LOGGER.error("failed to write transcript expected rates file: {}", e.toString());
        }
    }

    public void close()
    {
        closeBufferedWriter(mWriter);
    }


}