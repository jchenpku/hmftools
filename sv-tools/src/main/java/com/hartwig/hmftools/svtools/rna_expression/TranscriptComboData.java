package com.hartwig.hmftools.svtools.rna_expression;

import static com.hartwig.hmftools.linx.analysis.SvUtilities.appendStr;
import static com.hartwig.hmftools.svtools.rna_expression.FragmentMatchType.LONG;
import static com.hartwig.hmftools.svtools.rna_expression.FragmentMatchType.MAX_FRAG_TYPE;
import static com.hartwig.hmftools.svtools.rna_expression.FragmentMatchType.SHORT;
import static com.hartwig.hmftools.svtools.rna_expression.FragmentMatchType.SPLICED;
import static com.hartwig.hmftools.svtools.rna_expression.FragmentMatchType.UNSPLICED;
import static com.hartwig.hmftools.svtools.rna_expression.FragmentMatchType.typeAsInt;

import java.util.Arrays;
import java.util.List;

import com.google.common.collect.Lists;

public class TranscriptComboData
{
    private final List<Integer> mTranscripts;
    private final int[] mCounts;

    private final String mTranscriptsKey;

    public TranscriptComboData(final List<Integer> transcripts)
    {
        mTranscripts = transcripts;
        mCounts = new int[MAX_FRAG_TYPE];

        mTranscriptsKey = formTranscriptIds(transcripts);
    }

    public final List<Integer> getTranscriptIds() { return mTranscripts; }
    public final String getTranscriptsKey() { return mTranscriptsKey; }

    public boolean matches(final List<Integer> transcripts)
    {
        if(mTranscripts.size() != transcripts.size())
            return false;

        for(int transId : transcripts)
        {
            if(!mTranscripts.stream().anyMatch(x -> x == transId))
                return false;
        }

        return true;
    }

    public final int[] getCounts() { return mCounts; }

    public void addCounts(FragmentMatchType type, int count)
    {
        mCounts[typeAsInt(type)] += count;
    }

    public int getUnsplicedCount() { return mCounts[typeAsInt(UNSPLICED)]; }
    public int getShortCount() { return mCounts[typeAsInt(SHORT)]; }
    public int getSplicedCount() { return mCounts[typeAsInt(LONG)] + mCounts[typeAsInt(SPLICED)]; }

    public final int getCount(int type) { return type < mCounts.length ? mCounts[type] : 0; }
    public int totalCount() { return Arrays.stream(mCounts).sum(); }

    public static TranscriptComboData findMatchingData(final List<Integer> transcripts, final List<TranscriptComboData> dataList)
    {
        return dataList.stream().filter(x -> x.matches(transcripts)).findFirst().orElse(null);
    }

    public static String formTranscriptIds(final List<Integer> transcripts)
    {
        // convert into an order list of ints
        List<Integer> transIds = Lists.newArrayList();

        for (Integer transId : transcripts)
        {
            int index = 0;
            while (index < transIds.size())
            {
                if (transId < transIds.get(index))
                    break;

                ++index;
            }

            transIds.add(index, transId);
        }

        String transIdStr = "";
        for (Integer transId : transIds)
        {
            transIdStr = appendStr(transIdStr, String.valueOf(transId), '-');
        }

        return transIdStr;
    }

    public String toString()
    {
        return String.format("ids(%d: %s) counts(spl=%d short=%d long=%d unspl=%d)",
                mTranscripts.size(), mTranscriptsKey,
                mCounts[typeAsInt(SPLICED)], mCounts[typeAsInt(SHORT)], mCounts[typeAsInt(LONG)], mCounts[typeAsInt(UNSPLICED)]);
    }


}
