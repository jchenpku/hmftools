package com.hartwig.hmftools.linx.fusion;

import static java.lang.Math.abs;

import static com.hartwig.hmftools.common.variant.structural.annotation.GeneFusion.REPORTABLE_TYPE_KNOWN;
import static com.hartwig.hmftools.common.variant.structural.annotation.GeneFusion.REPORTABLE_TYPE_NONE;
import static com.hartwig.hmftools.linx.LinxConfig.CHECK_FUSIONS;
import static com.hartwig.hmftools.linx.LinxConfig.REF_GENOME_FILE;
import static com.hartwig.hmftools.linx.chaining.LinkFinder.getMinTemplatedInsertionLength;
import static com.hartwig.hmftools.linx.fusion.FusionFinder.couldBeReportable;
import static com.hartwig.hmftools.linx.fusion.FusionFinder.determineReportableFusion;
import static com.hartwig.hmftools.linx.fusion.FusionFinder.validFusionTranscript;
import static com.hartwig.hmftools.linx.fusion.FusionWriter.convertBreakendsAndFusions;
import static com.hartwig.hmftools.linx.gene.SvGeneTranscriptCollection.PRE_GENE_PROMOTOR_DISTANCE;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_END;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_START;
import static com.hartwig.hmftools.linx.types.SvVarData.isStart;
import static com.hartwig.hmftools.linx.visualiser.file.VisualiserWriter.GENE_TYPE_FUSION;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hartwig.hmftools.common.utils.PerformanceCounter;
import com.hartwig.hmftools.common.variant.structural.annotation.FusionAnnotations;
import com.hartwig.hmftools.common.variant.structural.annotation.FusionChainInfo;
import com.hartwig.hmftools.common.variant.structural.annotation.FusionTermination;
import com.hartwig.hmftools.common.variant.structural.annotation.GeneAnnotation;
import com.hartwig.hmftools.common.variant.structural.annotation.GeneFusion;
import com.hartwig.hmftools.common.variant.structural.annotation.ImmutableFusionAnnotations;
import com.hartwig.hmftools.common.variant.structural.annotation.ImmutableFusionChainInfo;
import com.hartwig.hmftools.common.variant.structural.annotation.ImmutableFusionTermination;
import com.hartwig.hmftools.common.variant.structural.annotation.Transcript;
import com.hartwig.hmftools.common.variant.structural.linx.LinxBreakend;
import com.hartwig.hmftools.common.variant.structural.linx.LinxFusion;
import com.hartwig.hmftools.linx.gene.SvGeneTranscriptCollection;
import com.hartwig.hmftools.linx.neoepitope.NeoEpitopeFinder;
import com.hartwig.hmftools.linx.neoepitope.RefGenomeSource;
import com.hartwig.hmftools.linx.rna.RnaFusionMapper;
import com.hartwig.hmftools.linx.types.SvBreakend;
import com.hartwig.hmftools.linx.chaining.SvChain;
import com.hartwig.hmftools.linx.types.SvCluster;
import com.hartwig.hmftools.linx.types.SvLinkedPair;
import com.hartwig.hmftools.linx.types.SvVarData;
import com.hartwig.hmftools.linx.LinxConfig;
import com.hartwig.hmftools.linx.visualiser.file.VisFusionFile;
import com.hartwig.hmftools.linx.visualiser.file.VisualiserWriter;
import com.hartwig.hmftools.patientdb.dao.DatabaseAccess;
import com.hartwig.hmftools.patientdb.dao.StructuralVariantFusionDAO;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import htsjdk.samtools.reference.IndexedFastaSequenceFile;

public class FusionDisruptionAnalyser
{
    private FusionFinder mFusionFinder;
    private DisruptionFinder mDisruptionFinder;
    private FusionWriter mFusionWriter;
    private NeoEpitopeFinder mNeoEpitopeFinder;
    private boolean mValidState;

    private String mSampleId;
    private final String mOutputDir;
    private final SvGeneTranscriptCollection mGeneTransCollection;
    private Map<String, List<SvBreakend>> mChrBreakendMap;
    private LinxConfig mConfig;

    private final FusionParameters mFusionParams;
    private boolean mLogReportableOnly;
    private boolean mLogAllPotentials;
    private boolean mLogRepeatedGenePairs;
    private List<String> mRestrictedGenes;
    private boolean mFindNeoEpitopes;

    private final List<GeneFusion> mFusions; // all possible valid transcript-pair fusions
    private final List<GeneFusion> mUniqueFusions; // top-priority fusions from within each unique gene and SV pair
    private final Map<GeneFusion,String> mInvalidFusions;

    private RnaFusionMapper mRnaFusionMapper;
    private VisualiserWriter mVisWriter;

    private PerformanceCounter mPerfCounter;

    public static final String SAMPLE_RNA_FILE = "sample_rna_file";
    public static final String PRE_GENE_BREAKEND_DISTANCE = "fusion_gene_distance";
    public static final String RESTRICTED_GENE_LIST = "restricted_fusion_genes";
    public static final String LOG_REPORTABLE_ONLY = "log_reportable_fusions";
    public static final String LOG_ALL_POTENTIALS = "log_all_potential_fusions";
    public static final String LOG_REPEAT_GENE_PAIRS = "log_repeat_gene_pairs";
    public static final String LOG_INVALID_REASONS = "log_invalid_fusions";
    public static final String SKIP_UNPHASED_FUSIONS = "skip_unphased_fusions";
    public static final String NEO_EPITOPES = "neo_epitopes";

    private static final Logger LOGGER = LogManager.getLogger(FusionDisruptionAnalyser.class);

    public FusionDisruptionAnalyser(final CommandLine cmdLineArgs, final LinxConfig config,
            SvGeneTranscriptCollection ensemblDataCache, VisualiserWriter writer)
    {
        mOutputDir = config.OutputDataPath;

        mConfig = config;
        mGeneTransCollection = ensemblDataCache;
        mFusionFinder = new FusionFinder(cmdLineArgs, ensemblDataCache);
        mFusionWriter = new FusionWriter(mOutputDir);
        mDisruptionFinder = new DisruptionFinder(cmdLineArgs, ensemblDataCache, mOutputDir);
        mVisWriter = writer;

        mNeoEpitopeFinder = null;

        mFusions = Lists.newArrayList();
        mUniqueFusions = Lists.newArrayList();
        mInvalidFusions = Maps.newHashMap();
        mLogReportableOnly = false;
        mLogAllPotentials = false;
        mLogRepeatedGenePairs = false;
        mFindNeoEpitopes = false;
        mFusionParams = new FusionParameters();
        mFusionParams.RequireUpstreamBiotypes = true;

        mRnaFusionMapper = null;

        mPerfCounter = new PerformanceCounter("Fusions");

        mChrBreakendMap = null;
        mRestrictedGenes = Lists.newArrayList();

        mValidState = true;
        initialise(cmdLineArgs);
    }

    public static void addCmdLineArgs(Options options)
    {
        options.addOption(SAMPLE_RNA_FILE, true, "Sample RNA data to match");
        options.addOption(PRE_GENE_BREAKEND_DISTANCE, true, "Distance after to a breakend to consider in a gene");
        options.addOption(RESTRICTED_GENE_LIST, true, "Restrict fusion search to specific genes");
        options.addOption(SKIP_UNPHASED_FUSIONS, false, "Skip unphased fusions");
        options.addOption(NEO_EPITOPES, false, "Search for neo-epitopes from fusions");
        options.addOption(REF_GENOME_FILE, true, "Reference genome file");
        options.addOption(LOG_REPORTABLE_ONLY, false, "Only write out reportable fusions");
        options.addOption(LOG_ALL_POTENTIALS, false, "Log all potential fusions");
        options.addOption(LOG_REPEAT_GENE_PAIRS, false, "Log sme gene-pair repeatedly if supported by different SVs");
        options.addOption(LOG_INVALID_REASONS, false, "Log reasons for not making a fusion between transcripts");
    }

    private void initialise(final CommandLine cmdLineArgs)
    {
        if(cmdLineArgs == null)
            return;

        if (cmdLineArgs.hasOption(PRE_GENE_BREAKEND_DISTANCE))
        {
            int preGeneBreakendDistance = Integer.parseInt(cmdLineArgs.getOptionValue(PRE_GENE_BREAKEND_DISTANCE));
            PRE_GENE_PROMOTOR_DISTANCE = preGeneBreakendDistance;
        }

        if(cmdLineArgs.hasOption(RESTRICTED_GENE_LIST))
        {
            String restrictedGenesStr = cmdLineArgs.getOptionValue(RESTRICTED_GENE_LIST);
            mRestrictedGenes = Arrays.stream(restrictedGenesStr.split(";")).collect(Collectors.toList());

            LOGGER.info("restricting fusion genes to: {}", restrictedGenesStr);
        }

        mLogReportableOnly = cmdLineArgs.hasOption(LOG_REPORTABLE_ONLY);
        mFusionParams.RequirePhaseMatch = cmdLineArgs.hasOption(SKIP_UNPHASED_FUSIONS);
        mLogAllPotentials = cmdLineArgs.hasOption(LOG_ALL_POTENTIALS);
        mLogRepeatedGenePairs = cmdLineArgs.hasOption(LOG_REPEAT_GENE_PAIRS);

        if(cmdLineArgs.hasOption(LOG_INVALID_REASONS))
        {
            mFusionFinder.setLogInvalidReasons(true);
            mFusionParams.LogInvalidReasons = cmdLineArgs.hasOption(LOG_INVALID_REASONS);
        }

        if(mConfig.hasMultipleSamples() || mLogAllPotentials)
        {
            mFusionWriter.initialiseOutputFiles();
        }

        if(mConfig.hasMultipleSamples())
        {
            mDisruptionFinder.initialiseOutputFile("LNX_DISRUPTIONS.csv");
        }

        if (cmdLineArgs.hasOption(SAMPLE_RNA_FILE))
        {
            mRnaFusionMapper = new RnaFusionMapper(mGeneTransCollection, mFusionFinder, mUniqueFusions, mInvalidFusions);
            mRnaFusionMapper.setOutputDir(mOutputDir);
            mRnaFusionMapper.loadSampleRnaData(cmdLineArgs.getOptionValue(SAMPLE_RNA_FILE));
        }

        mFindNeoEpitopes = cmdLineArgs.hasOption(NEO_EPITOPES);

        if(mFindNeoEpitopes && cmdLineArgs.hasOption(REF_GENOME_FILE))
        {
            try
            {
                IndexedFastaSequenceFile refGenomeFile =
                        new IndexedFastaSequenceFile(new File(cmdLineArgs.getOptionValue(REF_GENOME_FILE)));
                RefGenomeSource refGenome = new RefGenomeSource(refGenomeFile);
                mNeoEpitopeFinder = new NeoEpitopeFinder(refGenome, mGeneTransCollection, mOutputDir);
            }
            catch(IOException e)
            {
                LOGGER.error("failed to load ref genome: {}", e.toString());
                mValidState = false;
            }
        }

        if(cmdLineArgs.hasOption(CHECK_FUSIONS) && !mFusionFinder.hasValidConfigData())
            mValidState = false;
    }

    public boolean hasRnaSampleData() { return mRnaFusionMapper != null; }
    public final Set<String> getRnaSampleIds() { return mRnaFusionMapper.getSampleRnaData().keySet(); }
    public final List<GeneFusion> getFusions() { return mFusions; }
    public boolean validState() { return mValidState; }

    public final FusionFinder getFusionFinder() { return mFusionFinder; }
    public final DisruptionFinder getDisruptionFinder() { return mDisruptionFinder; }

    public void annotateTranscripts(final List<SvVarData> svList, boolean purgeInvalidTranscripts)
    {
        // mark any transcripts as not disruptive prior to running any fusion logic
        mDisruptionFinder.markTranscriptsDisruptive(svList);

        for(final SvVarData var : svList)
        {
            // now that transcripts have been marked as disruptive it is safe to purge any which cannot make viable fusions
            if (purgeInvalidTranscripts)
            {
                for (int be = SE_START; be <= SE_END; ++be)
                {
                    if (be == SE_END && var.isSglBreakend())
                        continue;

                    boolean isStart = isStart(be);

                    List<GeneAnnotation> genesList = var.getGenesList(isStart);

                    for (GeneAnnotation gene : genesList)
                    {
                        int transIndex = 0;
                        while (transIndex < gene.transcripts().size())
                        {
                            Transcript transcript = gene.transcripts().get(transIndex);

                            // only retain transcript which are potential fusion candidates (with exception for canonical)
                            if (!transcript.isDisruptive() && !validFusionTranscript(transcript) && !transcript.isCanonical())
                            {
                                gene.transcripts().remove(transIndex);
                            }
                            else
                            {
                                ++transIndex;
                            }
                        }
                    }
                }
            }
        }
    }

    public void run(final String sampleId, final List<SvVarData> svList, final DatabaseAccess dbAccess,
            final List<SvCluster> clusters, Map<String, List<SvBreakend>> chrBreakendMap)
    {
        mPerfCounter.start();

        mSampleId = sampleId;
        mChrBreakendMap = chrBreakendMap;

        mUniqueFusions.clear();
        findFusions(svList, clusters);
        mDisruptionFinder.findReportableDisruptions(svList);

        mUniqueFusions.addAll(extractUniqueFusions());

        // add protein information which won't have been set for unreported fusions
        mUniqueFusions.stream().filter(x -> !x.reportable()).forEach(x -> mFusionFinder.setFusionProteinFeatures(x));

        final List<Transcript> transcripts = getTranscriptList(svList, mUniqueFusions);

        final List<LinxBreakend> breakends = Lists.newArrayList();
        final List<LinxFusion> fusions = Lists.newArrayList();
        convertBreakendsAndFusions(mUniqueFusions, transcripts, fusions, breakends);

        if(mConfig.isSingleSample())
        {
            mFusionWriter.writeSampleData(mSampleId, mUniqueFusions, fusions, breakends);
            mDisruptionFinder.writeSampleData(mSampleId);

            if(mLogAllPotentials)
            {
                mFusions.forEach(x -> mFusionWriter.writeVerboseFusionData(x, mSampleId));
            }
        }
        else
        {
            // write fusions in detail when in batch mode
            final List<GeneFusion> fusionList = mLogAllPotentials ? mFusions : mUniqueFusions;

            fusionList.stream()
                    .filter(x -> x.reportable() || !mLogReportableOnly)
                    .forEach(x -> mFusionWriter.writeVerboseFusionData(x, mSampleId));

            mDisruptionFinder.writeMultiSampleData(mSampleId, svList);
        }

        addVisualisationData(mUniqueFusions);

        if(LOGGER.isDebugEnabled())
        {
            for(final GeneFusion fusion : mUniqueFusions)
            {
                if(fusion.knownType() == REPORTABLE_TYPE_NONE)
                    continue;

                LOGGER.debug("fusion({}-{}) reportable({}) knownType({}) cluster({} sv={}) SVs({} & {})",
                        fusion.upstreamTrans().gene().GeneName, fusion.downstreamTrans().gene().GeneName, fusion.reportable(),
                        fusion.knownType(), fusion.getAnnotations().clusterId(), fusion.getAnnotations().clusterCount(),
                        fusion.upstreamTrans().gene().id(), fusion.downstreamTrans().gene().id());
            }
        }

        if(dbAccess != null && mConfig.UploadToDB)
        {
            LOGGER.debug("persisting {} breakends and {} fusions to database", breakends.size(), fusions.size());

            final StructuralVariantFusionDAO annotationDAO = new StructuralVariantFusionDAO(dbAccess.context());
            annotationDAO.writeBreakendsAndFusions(mSampleId, breakends, fusions);
        }

        if(mRnaFusionMapper != null)
            mRnaFusionMapper.assessRnaFusions(sampleId, chrBreakendMap);

        if(mNeoEpitopeFinder != null)
            mNeoEpitopeFinder.reportNeoEpitopes(mSampleId, mFusions);

        mChrBreakendMap = null;

        mPerfCounter.stop();
    }

    private void findFusions(final List<SvVarData> svList, final List<SvCluster> clusters)
    {
        if(mSampleId.isEmpty() || mFusionFinder == null)
            return;

        mFusions.clear();
        mInvalidFusions.clear();

        boolean checkSoloSVs = true;
        boolean checkClusters = true;

        if(checkSoloSVs)
        {
            finalSingleSVFusions(svList);
        }

        if(checkClusters)
        {
            findChainedFusions(clusters);
        }
    }

    private void finalSingleSVFusions(final List<SvVarData> svList)
    {
        // always report SVs by themselves
        for (final SvVarData var : svList)
        {
            if (var.isSglBreakend())
                continue;

            // skip SVs which have been chained
            if(var.getCluster().getSvCount() > 1 && var.getCluster().findChain(var) != null)
                continue;

            List<GeneAnnotation> genesListStart = Lists.newArrayList(var.getGenesList(true));
            List<GeneAnnotation> genesListEnd = Lists.newArrayList(var.getGenesList(false));

            applyGeneRestrictions(genesListStart);
            applyGeneRestrictions(genesListEnd);

            if(genesListStart.isEmpty() || genesListEnd.isEmpty())
                continue;

            List<GeneFusion> fusions = mFusionFinder.findFusions(genesListStart, genesListEnd, mFusionParams, true);

            if(mNeoEpitopeFinder != null)
                mNeoEpitopeFinder.checkFusions(fusions, genesListStart, genesListEnd);

            if (fusions.isEmpty())
                continue;

            if(mLogReportableOnly)
            {
                fusions = fusions.stream().filter(GeneFusion::reportable).collect(Collectors.toList());
            }

            final SvCluster cluster = var.getCluster();

            // check transcript disruptions
            for (final GeneFusion fusion : fusions)
            {
                FusionTermination[] terminationInfo = {null, null};

                for(int i = 0; i <=1; ++i)
                {
                    boolean isUpstream = (i == 0);

                    // look at each gene in turn
                    Transcript transcript = isUpstream ? fusion.upstreamTrans() : fusion.downstreamTrans();
                    GeneAnnotation gene = isUpstream ? fusion.upstreamTrans().gene() : fusion.downstreamTrans().gene();

                    SvBreakend breakend = var.getBreakend(gene.isStart());

                    terminationInfo[i] = checkTranscriptDisruptionInfo(breakend, transcript);
                }

                FusionAnnotations annotations = ImmutableFusionAnnotations.builder()
                        .clusterId(cluster.id())
                        .clusterCount(cluster.getSvCount())
                        .resolvedType(cluster.getResolvedType().toString())
                        .chainInfo(null)
                        .disruptionUp(terminationInfo[0])
                        .disruptionDown(terminationInfo[1])
                        .build();

                fusion.setAnnotations(annotations);
                mFusions.add(fusion);
            }
        }
    }

    private void findChainedFusions(final List<SvCluster> clusters)
    {
        // for now only consider simple SVs and resolved small clusters
        for (final SvCluster cluster : clusters)
        {
            if (cluster.getSvCount() == 1) // simple clusters already checked
                continue;

            if (cluster.getChains().isEmpty())
                continue;

            List<GeneFusion> chainFusions = Lists.newArrayList();

            for (final SvChain chain : cluster.getChains())
            {
                findChainedFusions(cluster, chain, chainFusions);
            }

            if(chainFusions.isEmpty())
                continue;

            // now all fusions have been gathered from this chain, set the reportable one (if any)
            LOGGER.trace("cluster({}) found {} chained fusions", cluster.id(), chainFusions.size());

            // consider fusions from amongst unique gene-pairings
            List<String> genePairings = Lists.newArrayList();

            for(int i = 0; i < chainFusions.size(); ++i)
            {
                GeneFusion fusion = chainFusions.get(i);
                final String genePair = fusion.name();

                if(genePairings.contains(genePair))
                    continue;

                genePairings.add(genePair);

                // gather up all matching fusions
                List<GeneFusion> genePairFusions = Lists.newArrayList();
                genePairFusions.add(fusion);

                for(int j = i+1; j < chainFusions.size(); ++j)
                {
                    GeneFusion nextFusion = chainFusions.get(j);
                    String nextGenePair = nextFusion.name();

                    if(nextGenePair.equals(genePair))
                    {
                        genePairFusions.add(nextFusion);
                    }
                }

                // only chained fusions with unterminated ends and valid traversal are considered as reportable
                mFusionFinder.setReportableGeneFusions(genePairFusions);
            }

            mFusions.addAll(chainFusions.stream()
                    .filter(x -> !mLogReportableOnly || x.reportable())
                    .filter(x -> x.getAnnotations() != null)
                    .collect(Collectors.toList()));
        }
    }

    private static int FUSION_MAX_CHAIN_LENGTH = 100000;

    private void findChainedFusions(final SvCluster cluster, final SvChain chain, List<GeneFusion> chainFusions)
    {
        // look for fusions formed by breakends connected in a chain

        // given a chain sAe - sBe - sCe - sDe, where As and De are the open ends of the chain, the following fusions need to be tested:
        // a) each SV in isolation, ie as a single SV
        // b) the left-most / lower breakend of each SV with with right-most / upper breakend of SVs higher up in the chain

        // whenever a linked pair is traversed by a fusion, it cannot touch or traverse genic regions without disrupting the fusion
        final List<SvLinkedPair> linkedPairs = chain.getLinkedPairs();

        for (int lpIndex1 = 0; lpIndex1 <= linkedPairs.size(); ++lpIndex1)
        {
            SvVarData lowerSV = null;
            SvBreakend lowerBreakend = null;

            // the lower link takes the other breakend of the current linked pair's 'first' SV
            // and in order for it to also test the last SV in isolation, also takes the last pair's second (upper) breakend
            if(lpIndex1 < linkedPairs.size())
            {
                SvLinkedPair pair = linkedPairs.get(lpIndex1);
                lowerSV = pair.first();
                lowerBreakend = pair.first().getBreakend(!pair.firstLinkOnStart());
            }
            else
            {
                SvLinkedPair prevPair = linkedPairs.get(lpIndex1 - 1);
                lowerSV = prevPair.second();
                lowerBreakend = prevPair.secondBreakend();
            }

            if (lowerSV.isSglBreakend())
                continue;

            List<GeneAnnotation> genesListLower = Lists.newArrayList(lowerSV.getGenesList(lowerBreakend.usesStart()));
            applyGeneRestrictions(genesListLower);

            if (genesListLower.isEmpty())
                continue;

            List<SvLinkedPair> traversedPairs = Lists.newArrayList();

            for (int lpIndex2 = lpIndex1; lpIndex2 <= linkedPairs.size(); ++lpIndex2)
            {
                SvVarData upperSV = null;
                SvBreakend upperBreakend = null;

                // the upper link takes the breakend of the current linked pair's 'second' SV
                // and beyond all the links, it must also test fusions with the chain's upper open breakend
                if(lpIndex2 < linkedPairs.size())
                {
                    SvLinkedPair pair = linkedPairs.get(lpIndex2);
                    upperSV = pair.first();
                    upperBreakend = pair.firstBreakend();
                }
                else
                {
                    // at the last link, take the open breakend of the chain
                    upperBreakend = chain.getOpenBreakend(false);

                    if(upperBreakend == null) // can be null for SGLs at end of chain
                        break;

                    upperSV = upperBreakend.getSV();
                }

                List<GeneAnnotation> genesListUpper = Lists.newArrayList(upperSV.getGenesList(upperBreakend.usesStart()));
                applyGeneRestrictions(genesListUpper);

                // if a new linked pair has been traversed to reach this upper breakend, record its length
                // and test whether it traverses any genic region, and if so invalidate the fusion
                if(lpIndex2 > lpIndex1)
                {
                    SvLinkedPair lastPair = linkedPairs.get(lpIndex2 - 1);
                    traversedPairs.add(lastPair);
                }

                if(genesListUpper.isEmpty())
                {
                    // skip past this link and breakend to the next one, keeping the possibility of a fusion with the lower breakend open
                    continue;
                }

                /*
                if(lpIndex2 > lpIndex1)
                {
                    LOGGER.debug("cluster({}) chain({}) testing chained fusion: be1({} {}) & be2({} {}) link indices({} -> {})",
                            cluster.id(), chain.id(), lowerBreakend.toString(), genesListLower.get(0).GeneName,
                            upperBreakend.toString(), genesListUpper.get(0).GeneName, lpIndex1, lpIndex2);
                }
                */

                // test the fusion between these 2 breakends
                List<GeneFusion> fusions = mFusionFinder.findFusions(genesListLower, genesListUpper, mFusionParams, false);

                if(mNeoEpitopeFinder != null)
                    mNeoEpitopeFinder.checkFusions(fusions, genesListLower, genesListUpper);

                if(fusions.isEmpty())
                    continue;

                if (lpIndex2 > lpIndex1)
                {
                    // a chain cannot be an exon-exon fusion, so cull any of these
                    fusions = fusions.stream().filter(x -> !x.isExonic()).collect(Collectors.toList());
                }

                if(mLogReportableOnly)
                {
                    fusions = fusions.stream().filter(x -> couldBeReportable(x)).collect(Collectors.toList());

                    if(fusions.isEmpty())
                        continue;
                }

                int validTraversalFusionCount = 0; // between these 2 SVs

                for (GeneFusion fusion : fusions)
                {
                    // if the fusion from the upstream gene is on the positive strand, then it will have a fusion direction of +1
                    // whenever it goes through a subsequent linked pair by joining to the first (lower) breakend in the pair
                    int upGeneStrand = fusion.upstreamTrans().gene().Strand;
                    boolean isPrecodingUpstream = fusion.upstreamTrans().preCoding();
                    boolean fusionLowerToUpper = fusion.upstreamTrans().gene().position() == lowerBreakend.position();

                    // check any traversed genes
                    long totalLinkLength = 0;
                    boolean validTraversal = true;
                    boolean allTraversalAssembled = true;

                    for(SvLinkedPair pair : traversedPairs)
                    {
                        totalLinkLength += pair.length();

                        if(pair.isInferred())
                            allTraversalAssembled = false;

                        // if going lower to upper, if the orientation of the first breakend in the pair is opposite to the strand of
                        // the upstream gene, then the fusion direction for that pair is the same as a the upstream gene
                        // otherwise it needs to be switched
                        int fusionDirection = 0;

                        if(fusionLowerToUpper)
                        {
                            fusionDirection = pair.firstBreakend().orientation() != upGeneStrand ? upGeneStrand : -upGeneStrand;
                        }
                        else
                        {
                            fusionDirection = pair.secondBreakend().orientation() != upGeneStrand ? upGeneStrand : -upGeneStrand;
                        }

                        // any invalid traversal causes this fusion to be entirely skipped from further analysis
                        if(mDisruptionFinder.pairTraversesGene(pair, fusionDirection, isPrecodingUpstream))
                        {
                            validTraversal = false;
                            break;
                        }
                    }

                    if(!validTraversal)
                    {
                        recordInvalidFusion(fusion, "InvalidTraversal");
                        continue;
                    }

                    ++validTraversalFusionCount;

                    FusionTermination[] terminationInfo = {null, null};

                    for (int se = SE_START; se <= SE_END; ++se)
                    {
                        boolean isUpstream = (se == 0);

                        // look at each gene in turn
                        Transcript transcript = isUpstream ? fusion.upstreamTrans() : fusion.downstreamTrans();
                        GeneAnnotation gene = isUpstream ? fusion.upstreamTrans().gene() : fusion.downstreamTrans().gene();

                        SvBreakend breakend = lowerBreakend.position() == gene.position() ? lowerBreakend : upperBreakend;

                        boolean isChainEnd = (breakend == lowerBreakend && lpIndex1 == 0)
                                || (breakend == upperBreakend && lpIndex2 == linkedPairs.size());

                        if (isChainEnd)
                        {
                            terminationInfo[se] = checkTranscriptDisruptionInfo(breakend, transcript);
                        }
                        else
                        {
                            // looks from this link outwards past the end of the transcript for any invalidation of the transcript
                            int linkIndex = breakend == lowerBreakend ? lpIndex1 - 1 : lpIndex2;
                            terminationInfo[se] = checkTranscriptDisruptionInfo(breakend, transcript, chain, linkIndex);
                        }
                    }

                    int linksCount = lpIndex2 - lpIndex1;

                    FusionChainInfo chainInfo = ImmutableFusionChainInfo.builder()
                            .chainId(chain.id())
                            .chainLinks(linksCount)
                            .chainLength((int)totalLinkLength)
                            .traversalAssembled(allTraversalAssembled)
                            .validTraversal(validTraversal)
                            .build();

                    FusionAnnotations annotations = ImmutableFusionAnnotations.builder()
                            .clusterId(cluster.id())
                            .clusterCount(cluster.getSvCount())
                            .resolvedType(cluster.getResolvedType().toString())
                            .chainInfo(chainInfo)
                            .disruptionUp(terminationInfo[SE_START])
                            .disruptionDown(terminationInfo[SE_END])
                            .build();

                    fusion.setAnnotations(annotations);

                    // accept invalidated chains and transcripts for known fusions
                    boolean isKnown = fusion.knownType() == REPORTABLE_TYPE_KNOWN;
                    boolean chainLengthOk =  totalLinkLength <= FUSION_MAX_CHAIN_LENGTH;
                    boolean notTerminated = !fusion.isTerminated();

                    if(validTraversal && ((chainLengthOk && notTerminated) || isKnown))
                    {
                        if(!hasIdenticalFusion(fusion, chainFusions))
                        {
                            chainFusions.add(fusion);
                        }
                    }
                    else
                    {
                        final String invalidReason = !validTraversal ? "TraversesSPA" :
                                (!chainLengthOk ? "LongChain" : "Terminated");

                        recordInvalidFusion(fusion, invalidReason);
                    }
                }

                if(validTraversalFusionCount == 0 && lpIndex2 > lpIndex1 && mRnaFusionMapper == null)
                {
                    // if there are no valid traversals between 2 indices, then any chain sections starting at the lower index
                    // will likewise be invalidated since can skip past this
                    LOGGER.trace("cluster({}) chain({}) no valid traversals between({} -> {})",
                            cluster.id(), chain.id(), lpIndex1, lpIndex2);

                    break;
                }
            }
        }
    }

    private void recordInvalidFusion(final GeneFusion fusion, final String reason)
    {
        if(mInvalidFusions.keySet().stream().anyMatch(x -> fusion.name().equals(x.name())))
            return;

        mInvalidFusions.put(fusion, reason);
    }

    private boolean hasIdenticalFusion(final GeneFusion newFusion, final List<GeneFusion> fusions)
    {
        for(GeneFusion fusion : fusions)
        {
            if(newFusion.upstreamTrans().gene().id() != fusion.upstreamTrans().gene().id())
                continue;

            if(newFusion.downstreamTrans().gene().id() != fusion.downstreamTrans().gene().id())
                continue;

            if(!newFusion.upstreamTrans().StableId.equals(fusion.upstreamTrans().StableId))
                continue;

            if(!newFusion.downstreamTrans().StableId.equals(fusion.downstreamTrans().StableId))
                continue;

            return true;
        }

        return false;
    }

    private void applyGeneRestrictions(List<GeneAnnotation> genesList)
    {
        if(mRestrictedGenes.isEmpty())
            return;

        int index = 0;
        while(index < genesList.size())
        {
            GeneAnnotation gene = genesList.get(index);

            if(mRestrictedGenes.contains(gene.GeneName))
                ++index;
            else
                genesList.remove(index);
        }
    }

    private FusionTermination checkTranscriptDisruptionInfo(final SvBreakend breakend, final Transcript transcript)
    {
        // check all breakends which fall within the bounds of this transcript, including any which are exonic
        List<SvBreakend> breakendList = mChrBreakendMap.get(breakend.chromosome());

        int totalBreakends = 0;
        int facingBreakends = 0;
        int disruptedExons = 0;
        long minDistance = -1;

        final SvCluster cluster = breakend.getCluster();

        int index = breakend.getChrPosIndex();

        while(true)
        {
            index += breakend.orientation() == -1 ? +1 : -1;

            if (index < 0 || index >= breakendList.size())
                break;

            SvBreakend nextBreakend = breakendList.get(index);

            // exit once the next breakend extends beyond the gene bounds
            if((breakend.orientation() == 1 && nextBreakend.position() < transcript.TranscriptStart)
            || (breakend.orientation() == -1 && nextBreakend.position() > transcript.TranscriptEnd))
            {
                break;
            }

            ++totalBreakends;

            if (nextBreakend.orientation() != breakend.orientation())
            {
                // skip breakends which cannot be chained by min TI length
                int minTiLength = getMinTemplatedInsertionLength(breakend, nextBreakend);
                long breakendDistance = abs(breakend.position() - nextBreakend.position());

                if(breakendDistance < minTiLength)
                    continue;

                if (minDistance == -1)
                    minDistance = breakendDistance;

                ++facingBreakends;
            }
        }

        boolean allLinksAssembled = false;

        if(facingBreakends > 0)
        {
            // is any facing breakend assembled?
            final SvLinkedPair tiLink = breakend.getSV().getLinkedPair(breakend.usesStart());
            allLinksAssembled = tiLink != null && tiLink.isAssembled();
        }

        return ImmutableFusionTermination.builder()
                .allLinksAssembled(allLinksAssembled)
                .facingBreakends(facingBreakends)
                .disruptedExons(disruptedExons)
                .totalBreakends(totalBreakends)
                .minDistance(minDistance)
                .transcriptTerminated(false)
                .build();
    }

    private FusionTermination checkTranscriptDisruptionInfo(
            final SvBreakend breakend, final Transcript transcript, final SvChain chain, int linkIndex)
    {
        // starting with this breakend and working onwards from it in the chain, check for any disruptions to the transcript
        // this includes subsequent links within the same chain and transcript
        SvLinkedPair startPair = chain.getLinkedPairs().get(linkIndex);
        boolean traverseUp = startPair.firstBreakend() == breakend; // whether to search up or down the chain

        int totalBreakends = 0;
        int facingBreakends = 0;
        int disruptedExons = 0;
        boolean transcriptTerminated = false;
        long minDistance = startPair.length();
        boolean allLinksAssembled = startPair.isAssembled();

        boolean isUpstream = transcript.isUpstream();

        while(linkIndex >= 0 && linkIndex <= chain.getLinkedPairs().size() - 1)
        {
            SvLinkedPair pair = chain.getLinkedPairs().get(linkIndex);

            if(pair.isInferred())
                allLinksAssembled = false;

            // identify next exon after this TI
            // the breakend's transcript info cannot be used because it faces the opposite way from the fusing breakend
            SvBreakend nextBreakend = traverseUp ? pair.secondBreakend() : pair.firstBreakend();

            // exit if the next breakend is now past the end of the transcript or if the breakend is down-stream of coding
            if(nextBreakend.orientation() == 1)
            {
                if(nextBreakend.position() > transcript.TranscriptEnd)
                    break;
                else if(!isUpstream && transcript.CodingEnd != null && nextBreakend.position() > transcript.CodingEnd)
                    break;
            }
            else
            {
                if(nextBreakend.position() < transcript.TranscriptStart)
                    break;
                else if(!isUpstream && transcript.CodingStart != null && nextBreakend.position() < transcript.CodingStart)
                    break;
            }

            transcriptTerminated = true;

            ++totalBreakends;
            ++facingBreakends;

            break;

            // no longer check that subsequent links within the same transcript are valid
        }

        return ImmutableFusionTermination.builder()
                .allLinksAssembled(allLinksAssembled)
                .facingBreakends(facingBreakends)
                .disruptedExons(disruptedExons)
                .totalBreakends(totalBreakends)
                .minDistance(minDistance)
                .transcriptTerminated(transcriptTerminated)
                .build();
    }

    private final List<GeneFusion> extractUniqueFusions()
    {
        // from the list of all potential fusions, collect up all reportable ones, and then the highest priority fusion
        // from amongst the those with the same gene-pairing and/or SV Id
        List<GeneFusion> uniqueFusions = mFusions.stream().filter(GeneFusion::reportable).collect(Collectors.toList());

        List<String> genePairs = Lists.newArrayList();

        if(!mLogRepeatedGenePairs)
            uniqueFusions.stream().forEach(x -> genePairs.add(x.name()));

        List<Integer> usedSvIds = Lists.newArrayList();
        uniqueFusions.stream().forEach(x -> usedSvIds.add(x.upstreamTrans().gene().id()));

        uniqueFusions.stream()
                .filter(x -> !usedSvIds.contains(x.downstreamTrans().gene().id()))
                .forEach(x -> usedSvIds.add(x.downstreamTrans().gene().id()));

        for(GeneFusion fusion : mFusions)
        {
            if(fusion.reportable() || genePairs.contains(fusion.name()))
                continue;

            if(usedSvIds.contains(fusion.upstreamTrans().gene().id()) || usedSvIds.contains(fusion.downstreamTrans().gene().id()))
                continue;

            // only add viable fusions for upload
            if(!fusion.isViable() || fusion.neoEpitopeOnly())
                continue;

            // gather up all other candidate fusions for this pairing and take the highest priority
            List<GeneFusion> similarFusions = Lists.newArrayList(fusion);

            similarFusions.addAll(mFusions.stream()
                    .filter(x -> !x.reportable())
                    .filter(x -> x.isViable() && !x.neoEpitopeOnly())
                    .filter(x -> x != fusion)
                    .filter(x -> x.name().equals(fusion.name()))
                    .filter(x -> !usedSvIds.contains(x.upstreamTrans().gene().id()) && !usedSvIds.contains(x.downstreamTrans().gene().id()))
                    .collect(Collectors.toList()));

            if(!mLogRepeatedGenePairs)
                genePairs.add(fusion.name());

            GeneFusion topFusion = determineReportableFusion(similarFusions, false);

            if(topFusion != null)
            {
                uniqueFusions.add(topFusion);

                usedSvIds.add(topFusion.upstreamTrans().gene().id());

                if(!usedSvIds.contains(topFusion.downstreamTrans().gene().id()))
                    usedSvIds.add(topFusion.downstreamTrans().gene().id());
            }
        }

        return uniqueFusions;
    }

    public final List<Transcript> getTranscriptList(final List<SvVarData> svList, final List<GeneFusion> fusions)
    {
        // add all canonical transcript and then add any additional transcripts from the fusions
        List<Transcript> transcripts = Lists.newArrayList();

        for (SvVarData var : svList)
        {
            for (int be = SE_START; be <= SE_END; ++be)
            {
                for (GeneAnnotation geneAnnotation : var.getGenesList(isStart(be)))
                {
                    transcripts.addAll(geneAnnotation.transcripts().stream()
                            .filter(x -> x.isCanonical())
                            .collect(Collectors.toList()));
                }
            }
        }

        for(GeneFusion fusion : fusions)
        {
            if(!transcripts.contains(fusion.upstreamTrans()))
                transcripts.add(fusion.upstreamTrans());

            if(!transcripts.contains(fusion.downstreamTrans()))
                transcripts.add(fusion.downstreamTrans());
        }

        // transcripts not used in fusions won't have the exact exonic base set
        transcripts.stream().filter(Transcript::isExonic).forEach(x -> x.setExonicCodingBase());

        return transcripts;
    }

    private void addVisualisationData(final List<GeneFusion> fusionList)
    {
        if(mVisWriter == null || !mConfig.Output.WriteVisualisationData)
            return;

        final List<VisFusionFile> visFusions = Lists.newArrayList();

        for(final GeneFusion fusion : fusionList)
        {
            if (fusion.neoEpitopeOnly())
                return;

            if (fusion.reportable())
            {
                int clusterId = fusion.getAnnotations() != null ? fusion.getAnnotations().clusterId() : -1;

                final Transcript transUp = fusion.upstreamTrans();
                final Transcript transDown = fusion.downstreamTrans();

                mVisWriter.addGeneExonData(clusterId, transUp.gene().StableId, transUp.gene().GeneName,
                        transUp.StableId, transUp.TransId, transUp.gene().chromosome(), GENE_TYPE_FUSION);

                mVisWriter.addGeneExonData(clusterId, transDown.gene().StableId, transDown.gene().GeneName,
                        transDown.StableId, transDown.TransId, transDown.gene().chromosome(), GENE_TYPE_FUSION);

                visFusions.add(new VisFusionFile(
                        mSampleId, clusterId, fusion.reportable(),
                        transUp.geneName(), transUp.StableId, transUp.gene().chromosome(), transUp.gene().position(),
                        transUp.gene().Strand, transUp.regionType(), fusion.getFusedExon(true),
                        transDown.geneName(), transDown.StableId, transDown.gene().chromosome(), transDown.gene().position(),
                        transDown.gene().Strand, transDown.regionType(), fusion.getFusedExon(false)));
            }
        }

        mVisWriter.addFusions(visFusions);
    }

    public void close()
    {
        if(mConfig.hasMultipleSamples() || LOGGER.isDebugEnabled())
        {
            mPerfCounter.logStats();
        }

        if(mFusionFinder != null)
            mFusionWriter.close();

        if(mRnaFusionMapper != null)
            mRnaFusionMapper.close();

        if(mDisruptionFinder != null)
            mDisruptionFinder.close();

        if(mNeoEpitopeFinder != null)
            mNeoEpitopeFinder.close();
    }

    @VisibleForTesting
    public void setHasValidConfigData(boolean toggle) { mFusionFinder.setHasValidConfigData(toggle); }


}
