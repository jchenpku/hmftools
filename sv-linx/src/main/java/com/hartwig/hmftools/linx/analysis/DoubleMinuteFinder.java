package com.hartwig.hmftools.linx.analysis;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.pow;

import static com.hartwig.hmftools.common.utils.io.FileWriterUtils.closeBufferedWriter;
import static com.hartwig.hmftools.common.utils.io.FileWriterUtils.createBufferedWriter;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DEL;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DUP;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.typeAsInt;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.CHROMOSOME_ARM_P;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.appendStr;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.appendStrList;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.copyNumbersEqual;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.formatPloidy;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.getSvTypesStr;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.makeChrArmStr;
import static com.hartwig.hmftools.linx.types.ResolvedType.DOUBLE_MINUTE;
import static com.hartwig.hmftools.linx.types.SvCluster.CLUSTER_ANNOT_BFB;
import static com.hartwig.hmftools.linx.types.SvCluster.CLUSTER_ANNOT_DM;
import static com.hartwig.hmftools.linx.types.SvLinkedPair.LINK_TYPE_TI;
import static com.hartwig.hmftools.linx.types.SvLinkedPair.copy;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_END;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_START;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hartwig.hmftools.common.purple.purity.PurityContext;
import com.hartwig.hmftools.common.variant.structural.StructuralVariantType;
import com.hartwig.hmftools.common.variant.structural.annotation.EnsemblGeneData;
import com.hartwig.hmftools.linx.chaining.ChainFinder;
import com.hartwig.hmftools.linx.chaining.SvChain;
import com.hartwig.hmftools.linx.cn.CnDataLoader;
import com.hartwig.hmftools.linx.cn.SvCNData;
import com.hartwig.hmftools.linx.cn.TelomereCentromereCnData;
import com.hartwig.hmftools.linx.gene.SvGeneTranscriptCollection;
import com.hartwig.hmftools.linx.types.DoubleMinuteData;
import com.hartwig.hmftools.linx.types.SvArmGroup;
import com.hartwig.hmftools.linx.types.SvBreakend;
import com.hartwig.hmftools.linx.types.SvCluster;
import com.hartwig.hmftools.linx.types.SvLinkedPair;
import com.hartwig.hmftools.linx.types.SvVarData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DoubleMinuteFinder
{
    private CnDataLoader mCnAnalyser;
    private SvGeneTranscriptCollection mGeneTransCache;
    private final ChainFinder mChainFinder;

    private final List<Integer> mProcessedClusters;
    private final Map<Integer, DoubleMinuteData> mDoubleMinutes;

    private String mOutputDir;
    private BufferedWriter mFileWriter;

    private static final double PLOIDY_THRESHOLD = 8;
    private static final double ADJACENT_PLOIDY_RATIO = 2.3;
    private static final int INF_PAIR_MIN_DISTANCE = 50000;

    private static final Logger LOGGER = LogManager.getLogger(DoubleMinuteFinder.class);

    public DoubleMinuteFinder()
    {
        mChainFinder = new ChainFinder();
        mCnAnalyser = null;
        mGeneTransCache = null;

        mProcessedClusters = Lists.newArrayList();
        mDoubleMinutes = Maps.newHashMap();

        mOutputDir = null;
        mFileWriter = null;
    }

    public void setGeneTransCache(final SvGeneTranscriptCollection geneTransCache) { mGeneTransCache = geneTransCache; }
    public void setCopyNumberAnalyser(CnDataLoader cnAnalyser) { mCnAnalyser = cnAnalyser; }
    public void setOutputDir(final String outputDir)
    {
        mOutputDir = outputDir;
    }

    public final Map<Integer, DoubleMinuteData> getDoubleMinutes() { return mDoubleMinutes; }

    public void clear()
    {
        mProcessedClusters.clear();
        mDoubleMinutes.clear();
    }

    public void analyseCluster(SvCluster cluster)
    {
        analyseCluster(cluster, false);
    }

    public void analyseCluster(SvCluster cluster, boolean reassess)
    {
        // look for DMs and alternatively consider annotating the cluster as BFB

        // don't re-analyse and cluster unless it has changed constituent SVs
        if(mProcessedClusters.contains(cluster.id()))
        {
            if(!reassess)
                return;

            cluster.setDoubleMinuteData(Lists.newArrayList(), null); // clear any previous DM data
            cluster.getAnnotationList().remove(CLUSTER_ANNOT_BFB);
            cluster.getAnnotationList().remove(CLUSTER_ANNOT_DM);
        }
        else
        {
            mProcessedClusters.add(cluster.id());
        }

        final List<SvVarData> candidateDMSVs = Lists.newArrayList();

        double clusterMaxPloidy = 0; // the max of the ploidy min values
        boolean possibleDM = false;

        if(cluster.getSvCount() == 1)
        {
            final SvVarData var = cluster.getSV(0);

            // early exit - only consider cluster-1 if a DUP
            if(var.type() == DUP && var.ploidyMax() >= PLOIDY_THRESHOLD && getAdjacentMajorAPRatio(var) >= ADJACENT_PLOIDY_RATIO)
            {
                candidateDMSVs.add(var);
                clusterMaxPloidy = var.ploidyMin();
                possibleDM = true;
            }
        }
        else
        {
            double maxInfPloidy = 0;
            final List<SvVarData> highPloidySVs = Lists.newArrayList();

            for (SvVarData var : cluster.getSVs())
            {
                if (var.isSglBreakend())
                    clusterMaxPloidy = max(clusterMaxPloidy, var.ploidyMin() * 0.5); // in case the SGL is a disguised foldback
                else
                    clusterMaxPloidy = max(clusterMaxPloidy, var.ploidyMin());

                if (var.isInferredSgl())
                    maxInfPloidy = max(maxInfPloidy, var.ploidy());

                if (var.ploidyMax() >= PLOIDY_THRESHOLD)
                    highPloidySVs.add(var);
            }

            // now attempt to find its boundaries, ie the SVs which
            // formed the DM by looking at ploidy and its ratio to adjacent major AP
            boolean hasHighMinPloidy = false;

            for (final SvVarData var : highPloidySVs)
            {
                if (var.ploidyMax() < clusterMaxPloidy && var.isFoldback())
                    continue;

                // at least one high-ploidy breakend must have a high ploidy relative to the
                // adjacent CN segment's major allele ploidy and a min ploidy above the threshold, not including DELs
                if(var.type() != DEL && var.ploidyMin() >= PLOIDY_THRESHOLD)
                    hasHighMinPloidy = true;

                double svAdjMAPRatio = getAdjacentMajorAPRatio(var);

                if(svAdjMAPRatio >= ADJACENT_PLOIDY_RATIO)
                    candidateDMSVs.add(var);
            }

            if(hasHighMinPloidy && !candidateDMSVs.isEmpty())
                possibleDM = true;
        }

        if(!possibleDM && cluster.getFoldbacks().isEmpty())
            return;

        // determine whether foldbacks and/or SGLs could explain the amplification, indicating a BFB process
        double sumFbPloidy = 0;
        double maxFbPloidy = 0;
        int foldbackCount = 0;
        double maxSglPloidy = 0;

        for (final SvVarData var : cluster.getSVs())
        {
            if(candidateDMSVs.contains(var))
                continue;

            if(var.isFoldback())
            {
                sumFbPloidy += var.isChainedFoldback() ? var.ploidy() * 0.5 : var.ploidy();
                foldbackCount += var.isChainedFoldback() ? 0.5 : 1;
                maxFbPloidy = max(maxFbPloidy, var.ploidy());
            }
            else if(var.isSglBreakend())
            {
                // limit the SGLs to the top 2 by ploidy
                maxSglPloidy = max(var.ploidy(), maxSglPloidy);
            }
        }

        // determine the maximum potential ploidy plausibly explained by BFB - taking the min of:
        // - 2x sum of FB ploidies + max INF /SGL ploidy
        // - 6x max FB ploidy
        // - HighestTelo/CentromereCN x 2^(FBCount + if(SGLPloidy > 10% * highest minPloidy,1,0))
        double maxArmEndCopyNumber = getMaxArmEndCopyNumber(cluster);
        int fbSglCount = foldbackCount + (maxSglPloidy > 0.1 * clusterMaxPloidy ? 1 : 0);
        double armBfbCopyNumber = maxArmEndCopyNumber * pow(2, fbSglCount);

        double maxBFBPloidy = min(2 * sumFbPloidy + maxSglPloidy, min(6 * maxFbPloidy, armBfbCopyNumber));

        if(maxBFBPloidy > clusterMaxPloidy)
        {
            LOGGER.debug(String.format("cluster(%s) BFB maxPloidy(%.1f) plausible ploidy(%.1f fb=%.1f sgl=%.1f arm=%.1f)",
                    cluster.id(), clusterMaxPloidy, maxBFBPloidy, sumFbPloidy, maxSglPloidy, armBfbCopyNumber));

            cluster.addAnnotation(CLUSTER_ANNOT_BFB);
            return;
        }

        if(!possibleDM)
            return;

        LOGGER.debug(String.format("cluster(%s) possible DM: maxPloidy(%.1f) dmSvCount(%d) maxBFBPloidy(%.1f fb=%.1f sgl=%.1f arm=%.1f)",
                cluster.id(), clusterMaxPloidy, candidateDMSVs.size(), maxBFBPloidy, sumFbPloidy, maxSglPloidy, armBfbCopyNumber));

        // other the criteria to be a DM are:
        // - NOT (INF=2) < 50k bases
        // - NOT amplifying a centromere or telomere if not a closed loop

        if(candidateDMSVs.size() == 2 && candidateDMSVs.get(0).isInferredSgl() && candidateDMSVs.get(1).isInferredSgl())
        {
            long distance = abs(candidateDMSVs.get(0).position(true) - candidateDMSVs.get(1).position(true));

            if(distance < INF_PAIR_MIN_DISTANCE)
            {
                LOGGER.debug(String.format("cluster(%s) possible DM: inf-pair distance(%d) too small",
                        cluster.id(), distance));
                return;
            }
        }

        final SvChain dmChain = createDMChain(cluster, candidateDMSVs);

        // a single DUP or a chain involving all high-ploidy SVs which can be made into a loop
        boolean fullyChained = false;

        if(dmChain != null)
        {
            if(candidateDMSVs.size() == 1)
            {
                fullyChained = true; // the single DUP case
            }
            else
            {
                if(candidateDMSVs.size() == 2 && candidateDMSVs.get(0).isSglBreakend() && candidateDMSVs.get(1).isSglBreakend())
                {
                    // pair of facing SGLs - likely a DUP
                    fullyChained = true;
                }
                else
                {
                    fullyChained = dmChain.getSvCount() == candidateDMSVs.size() && dmChain.isClosedLoop();
                    dmChain.logLinks();
                }
            }
        }
        // check that no arm has a high telomere or centromere relative to the DM's ploidy - make an exception for fully chained DMs
        if(!amplifiedVsSamplePloidy(cluster, clusterMaxPloidy) && !fullyChained)
        {
            if(clusterMaxPloidy < maxArmEndCopyNumber * ADJACENT_PLOIDY_RATIO)
            {
                LOGGER.debug("cluster({}}) possible DM: not amplified vs max armEndCopyNumber({}}) centro({}})",
                        cluster.id(), formatPloidy(maxArmEndCopyNumber));
                return;
            }
        }

        DoubleMinuteData dmData = new DoubleMinuteData(cluster, candidateDMSVs);
        dmData.MaxBFBPloidy = maxBFBPloidy;

        if(dmChain != null)
        {
            dmData.Chains.add(dmChain);
            dmData.FullyChained = fullyChained;
        }

        // collect up other possible DM SVs for logging only for now

        mDoubleMinutes.put(cluster.id(), dmData);

        cluster.setDoubleMinuteData(candidateDMSVs, fullyChained ? dmChain : null);

        cluster.addAnnotation(CLUSTER_ANNOT_DM);

        if(candidateDMSVs.size() == cluster.getSvCount())
        {
            cluster.setResolved(false, DOUBLE_MINUTE);
        }

        if(candidateDMSVs.size() == 1 && fullyChained)
        {
            // single DUPs won't go through the chaining routine so cache this chain here
            final SvVarData var = candidateDMSVs.get(0);
            dmChain.setPloidyData(var.ploidy(), var.ploidyUncertainty());
            cluster.addChain(dmChain, false);
        }

        LOGGER.debug(String.format("cluster(%s) identified DM: maxPloidy(%.1f) dmSvCount(%d) fullyChained(%s)",
                cluster.id(), clusterMaxPloidy, candidateDMSVs.size(), fullyChained));
    }

    private static double getAdjacentMajorAPRatio(final SvVarData var)
    {
        // get the largest ratio of ploidy to the adjacent major AP
        double maxRatio = 0;

        for (int se = SE_START; se <= SE_END; ++se)
        {
            if(se == SE_END && var.isSglBreakend())
                continue;

            final SvBreakend breakend = var.getBreakend(se);

            // gets the CN segment data on the lower side of the breakend (ie opposite to orientation)
            final SvCNData cnData = breakend.getSV().getCopyNumberData(breakend.usesStart(), breakend.orientation() == -1);

            if(cnData == null)
                continue;

            double adjacentMap = cnData.majorAllelePloidy();

            maxRatio = adjacentMap > 0 ? max(var.ploidy() / adjacentMap, maxRatio) : cnData.CopyNumber;
        }

        return maxRatio;
    }

    private double getMaxArmEndCopyNumber(final SvCluster cluster)
    {
        double maxCopyNumber = 0;

        for (SvArmGroup armGroup : cluster.getArmGroups())
        {
            final TelomereCentromereCnData tcData = mCnAnalyser.getChrTeleCentroData().get(armGroup.chromosome());

            if(tcData != null)
            {
                double centromereCopyNumber = armGroup.arm() == CHROMOSOME_ARM_P ? tcData.CentromerePArm : tcData.CentromereQArm;
                double telomereCopyNumber = armGroup.arm() == CHROMOSOME_ARM_P ? tcData.TelomerePArm : tcData.TelomereQArm;
                maxCopyNumber = max(max(telomereCopyNumber, centromereCopyNumber), maxCopyNumber);
            }
        }

        return maxCopyNumber;
    }

    private boolean amplifiedVsSamplePloidy(final SvCluster cluster, double dmPloidy)
    {
        final PurityContext samplePurity = mCnAnalyser.getPurityContext();
        if(samplePurity == null)
            return false;

        double samplePloidy = samplePurity.score().maxPloidy();
        if(dmPloidy > 50 * samplePloidy) // effectively disabled for now
        {
            LOGGER.debug("cluster({}}) DM amplified vs sample({}})",
                    cluster.id(), formatPloidy(samplePloidy));

            return true;
        }

        return false;
    }

    private final SvChain createDMChain(SvCluster cluster, List<SvVarData> dmSVList)
    {
        if(dmSVList.size() == 1)
        {
            // special case creating a chain out of a DUP
            SvChain chain = new SvChain(0);
            final SvVarData var = dmSVList.get(0);
            if(var.type() != DUP)
                return null;

            SvLinkedPair pair = new SvLinkedPair(var, var, LINK_TYPE_TI, true, false);
            chain.addLink(pair, true);
            chain.setPloidyData(var.ploidy(), var.ploidyUncertainty());
            return chain;
        }

        mChainFinder.initialise(cluster, dmSVList, false);
        mChainFinder.formChains(false);

        if(mChainFinder.getUniqueChains().size() != 1)
            return null;

        SvChain chain = mChainFinder.getUniqueChains().get(0);

        mChainFinder.clear();
        return chain;
    }

    public void reportCluster(final String sampleId, final SvCluster cluster)
    {
        if(mOutputDir == null || mOutputDir.isEmpty())
            return;

        if(!cluster.hasAnnotation(CLUSTER_ANNOT_DM))
            return;

        final DoubleMinuteData dmData = mDoubleMinutes.get(cluster.id());

        if(dmData == null)
            return;

        final SvChain chain = dmData.Chains.isEmpty() ? null : dmData.Chains.get(0);

        // a single DUP or a chain involving all high-ploidy SVs which can be made into a loop
        boolean chainsCentromere = false;

        if(chain != null)
        {
            if(dmData.SVs.size() == 1)
            {
                chainsCentromere = dmData.SVs.get(0).isCrossArm();
            }
            else
            {
                if(dmData.FullyChained)
                {
                    chainsCentromere = chain.getLinkedPairs().stream().anyMatch(x -> x.firstBreakend().arm() != x.secondBreakend().arm());
                }
            }
        }

        String svIds = "";
        final int[] typeCounts = new int[StructuralVariantType.values().length];
        final List<String> chromosomes = Lists.newArrayList();

        double minDMPloidy = 0;
        double maxDMPloidy = 0;
        double maxDMCopyNumber = 0;
        double minDMCopyNumber = 0;
        long minPosition = 0;
        long maxPosition = 0;

        for(final SvVarData var : dmData.SVs)
        {
            ++typeCounts[typeAsInt(var.type())];

            minDMPloidy = max(var.ploidyMin(), minDMPloidy);
            maxDMPloidy = max(var.ploidy(), maxDMPloidy);

            maxDMCopyNumber = max(maxDMCopyNumber, max(var.copyNumber(true), var.copyNumber(false)));

            double minCopyNumber = var.isSglBreakend() ? var.copyNumber(true) : min(var.copyNumber(true), var.copyNumber(false));
            if(minDMCopyNumber == 0 || minCopyNumber < minDMCopyNumber)
                minDMCopyNumber = minCopyNumber;

            for(int se = SE_START; se <= SE_END; ++se)
            {
                if(var.isSglBreakend() && se== SE_END)
                    continue;

                final SvBreakend breakend = var.getBreakend(se);
                final String chromosome = breakend.chromosome();

                if(!chromosomes.contains(chromosome))
                    chromosomes.add(chromosome);

                minPosition = minPosition == 0 ? breakend.position() : min(breakend.position(), minPosition);
                maxPosition = max(breakend.position(), maxPosition);
            }

            svIds = appendStr(svIds, var.idStr(), ';');
        }

        double sumFbPloidy = 0;
        double maxFbPloidy = 0;
        double sumSglPloidy = 0;
        double maxSglPloidy = 0;
        int nonDmSvsFullPloidy = 0;
        int nonDmSvsHalfPloidy = 0;
        double minAdjMAPRatio = 0;

        for(final SvVarData var : cluster.getSVs())
        {
            if(var.isFoldback())
            {
                sumFbPloidy += var.isChainedFoldback() ? var.ploidy() * 0.5 : var.ploidy();
                maxFbPloidy = max(var.ploidy(), maxFbPloidy);
            }

            if(var.isSglBreakend())
            {
                sumSglPloidy += var.ploidy();
                maxSglPloidy = max(var.ploidy(), maxSglPloidy);
            }

            if(!dmData.SVs.contains(var))
            {
                if(var.ploidyMax() >= minDMPloidy)
                    ++nonDmSvsFullPloidy;

                if(var.ploidyMax() >= minDMPloidy * 0.5)
                    ++nonDmSvsHalfPloidy;

                if((var.ploidy() >= dmData.MaxBFBPloidy || var.ploidy() > PLOIDY_THRESHOLD)
                && getAdjacentMajorAPRatio(var) >= ADJACENT_PLOIDY_RATIO)
                {
                    dmData.CandidateSVs.add(var);
                }
            }
            else
            {
                if(minAdjMAPRatio == 0)
                    minAdjMAPRatio = getAdjacentMajorAPRatio(var);
                else
                    minAdjMAPRatio = min(getAdjacentMajorAPRatio(var), minAdjMAPRatio);
            }
        }

        double maxCentroTeloCopyNumber = 0;

        for(final SvArmGroup armGroup : cluster.getArmGroups())
        {
            final TelomereCentromereCnData tcData = mCnAnalyser.getChrTeleCentroData().get(armGroup.chromosome());

            if (tcData != null)
            {
                if (armGroup.arm() == CHROMOSOME_ARM_P)
                {
                    maxCentroTeloCopyNumber = max(maxCentroTeloCopyNumber, max(tcData.TelomerePArm, tcData.CentromerePArm));
                }
                else
                {
                    maxCentroTeloCopyNumber = max(maxCentroTeloCopyNumber, max(tcData.TelomereQArm, tcData.CentromereQArm));
                }
            }
        }

        final String dmTypesStr = getSvTypesStr(typeCounts);

        final PurityContext purityContext = mCnAnalyser.getPurityContext();
        double samplePurity = purityContext != null ? purityContext.bestFit().purity() : 0;
        double samplePloidy = purityContext != null ? purityContext.bestFit().ploidy() : 0;

        long dmChainLength = chain != null ? chain.getLength(false) : 0;
        int chainSvCount = chain != null ? chain.getSvCount() : 0;

        // get amplified genes list by looking at all section traversed by this chain or breakends with genes in them?
        final String amplifiedGenesStr = chain != null ? getAmplifiedGenesList(chain) : "";

        final double chainData[] = chain != null ? getChainCharacteristics(cluster, chain, maxDMPloidy, dmData.SVs) : null;

        final String chromosomeStr = appendStrList(chromosomes, ';');

        String possibleSvTypes = "";

        if(!dmData.CandidateSVs.isEmpty())
        {
            final int[] possibleTypeCounts = new int[StructuralVariantType.values().length];

            for (final SvVarData var : dmData.CandidateSVs)
            {
                ++possibleTypeCounts[typeAsInt(var.type())];
            }

            possibleSvTypes = getSvTypesStr(possibleTypeCounts);
        }

        try
        {
            if (mFileWriter == null)
            {
                String outputFileName = mOutputDir;

                outputFileName += "LNX_DOUBLE_MINUTES.csv";

                mFileWriter = createBufferedWriter(outputFileName, false);

                mFileWriter.write("SampleId,ClusterId,ClusterDesc,ResolvedType,ClusterCount");
                mFileWriter.write(",SamplePurity,SamplePloidy,DMSvCount,DMSvTypes");
                mFileWriter.write(",FullyChained,ChainLength,ChainCount,SvIds,Chromosomes");
                mFileWriter.write(",MaxCopyNumber,MinPloidy,MaxPloidy,AmpGenes");
                mFileWriter.write(",ChainMinCnPercent,ChainDiffPloidies,ChainNonDMSVs,ChainNonDMSvDBs,UnchainedDBs");
                mFileWriter.write(",MaxBFBPloidy,FbCount,FbSumPloidy,FbMaxPloidy,SglCount,SglSumPloidy,SglMaxPloidy");
                mFileWriter.write(",MinPosition,MaxPosition,MaxTeloCentroCn,CrossCentro,MinAdjMAPRatio");
                mFileWriter.write(",PossibleSVs,NonDmSvsGtPloidy,NonDmSvsGtHalfPloidy");

                for(Integer cbr : CN_SEGMENT_BUCKETS)
                {
                    mFileWriter.write(String.format(",CNR_%d", cbr));
                }

                mFileWriter.newLine();
            }

            mFileWriter.write(String.format("%s,%d,%s,%s,%d",
                    sampleId, cluster.id(), cluster.getDesc(), cluster.getResolvedType(), cluster.getSvCount()));

            mFileWriter.write(String.format(",%.1f,%.1f,%d,%s",
                    samplePurity, samplePloidy, dmData.SVs.size(), dmTypesStr));

            mFileWriter.write(String.format(",%s,%d,%d,%s,%s",
                    dmData.FullyChained, dmChainLength, chainSvCount, svIds, chromosomeStr));

            mFileWriter.write(String.format(",%.1f,%.1f,%.1f,%s",
                    maxDMCopyNumber, minDMPloidy, maxDMPloidy, amplifiedGenesStr));

            if(chainData != null)
            {
                mFileWriter.write(String.format(",%.2f,%.0f,%.0f,%.0f,%.0f",
                        chainData[CHAIN_DATA_MIN_CN], chainData[CHAIN_DATA_DIFF_PLOIDIES],
                        chainData[CHAIN_DATA_NON_DM_SVS], chainData[CHAIN_DATA_NON_DM_DBS], chainData[CHAIN_DATA_UNCHAINED_DBS]));
            }
            else
            {
                mFileWriter.write(",0,0,0,0,0");
            }

            mFileWriter.write(String.format(",%.1f,%d,%.1f,%.1f,%d,%.1f,%.1f",
                    dmData.MaxBFBPloidy, cluster.getFoldbacks().size(), sumFbPloidy, maxFbPloidy,
                    cluster.getSglBreakendCount(), sumSglPloidy, maxSglPloidy));

            mFileWriter.write(String.format(",%d,%d,%.1f,%s,%.1f",
                    chromosomes.size() == 1 ? minPosition : 0, chromosomes.size() == 1 ? maxPosition : 0,
                    maxCentroTeloCopyNumber, chainsCentromere, minAdjMAPRatio));

            mFileWriter.write(String.format(",%s,%d,%d",
                    possibleSvTypes, nonDmSvsFullPloidy, nonDmSvsHalfPloidy));

            mFileWriter.write(String.format("%s",getCopyNumberSegmentData(cluster, samplePloidy, minDMCopyNumber)));

            mFileWriter.newLine();
        }
        catch (final IOException e)
        {
            LOGGER.error("error writing DM data: {}", e.toString());
        }
    }

    private static int CHAIN_DATA_MIN_CN = 0;
    private static int CHAIN_DATA_NON_DM_SVS = 1;
    private static int CHAIN_DATA_DIFF_PLOIDIES = 2;
    private static int CHAIN_DATA_NON_DM_DBS = 3;
    private static int CHAIN_DATA_UNCHAINED_DBS = 4;

    private double[] getChainCharacteristics(final SvCluster cluster, final SvChain chain, double maxDMPloidy, final List<SvVarData> dmSVs)
    {
        final double[] chainData = {1.0, 0, 0, 0, 0};

        if(cluster.getSvCount() == 1)
            return chainData;

        double minCopyNumber = maxDMPloidy;

        List<Double> diffPloidies = Lists.newArrayList();
        List<SvVarData> nonDmSVs = Lists.newArrayList();

        for(SvLinkedPair pair : chain.getLinkedPairs())
        {
            final SvBreakend lowerBreakend = pair.getBreakend(true);
            final SvBreakend upperBreakend = pair.getBreakend(false);

            final List<SvBreakend> breakendList = cluster.getChrBreakendMap().get(pair.chromosome());

            for(int i = lowerBreakend.getClusterChrPosIndex() + 1; i <= upperBreakend.getClusterChrPosIndex() - 1; ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                final SvVarData var = breakend.getSV();

                if(!chain.getSvList().contains(var))
                {
                    if (!nonDmSVs.contains(var))
                        nonDmSVs.add(var);

                    if (!diffPloidies.stream().anyMatch(x -> copyNumbersEqual(x, var.ploidy())))
                        diffPloidies.add(var.ploidy());
                }

                // ignore simple consecutive DELs
                if(i < upperBreakend.getClusterChrPosIndex() - 1)
                {
                    final SvBreakend nextBreakend = breakendList.get(i+1);

                    if(breakend.getSV().type() == DEL && breakend.getSV() == nextBreakend.getSV())
                    {
                        ++i;
                        continue;
                    }
                }

                minCopyNumber = min(max(breakend.copyNumberLowSide(), 0), minCopyNumber);
            }
        }

        chainData[CHAIN_DATA_MIN_CN] = minCopyNumber / maxDMPloidy;
        chainData[CHAIN_DATA_DIFF_PLOIDIES] = diffPloidies.size();
        chainData[CHAIN_DATA_NON_DM_SVS] = nonDmSVs.size();

        // check for DBs between non-DM SVs in segments of the chain
        long dbCount = nonDmSVs.stream()
                .filter(x -> x.getDBLink(true) != null)
                .filter(x -> nonDmSVs.contains(x.getDBLink(true).getOtherSV(x)))
                .count();

        dbCount += nonDmSVs.stream()
                .filter(x -> x.getDBLink(false) != null)
                .filter(x -> nonDmSVs.contains(x.getDBLink(false).getOtherSV(x)))
                .count();

        if(dbCount > 0)
            chainData[CHAIN_DATA_NON_DM_DBS] = dbCount * 0.5;

        List<SvVarData> unchainedSVs = cluster.getSVs().stream()
                .filter(x -> !dmSVs.contains(x))
                .filter(x -> !nonDmSVs.contains(x))
                .collect(Collectors.toList());

        dbCount = unchainedSVs.stream()
                .filter(x -> x.getDBLink(true) != null)
                .filter(x -> unchainedSVs.contains(x.getDBLink(true).getOtherSV(x)))
                .count();

        dbCount += unchainedSVs.stream()
                .filter(x -> x.getDBLink(false) != null)
                .filter(x -> unchainedSVs.contains(x.getDBLink(false).getOtherSV(x)))
                .count();

        if(dbCount > 0)
            chainData[CHAIN_DATA_UNCHAINED_DBS] = dbCount * 0.5;

        return chainData;
    }

    private final String getAmplifiedGenesList(final SvChain chain)
    {
        if(mGeneTransCache == null)
            return "";

        String genesStr = "";
        for(SvLinkedPair pair : chain.getLinkedPairs())
        {
            String chromosome = pair.chromosome();

            List<EnsemblGeneData> genesList = mGeneTransCache.findGenesByRegion(
                    chromosome, pair.getBreakend(true).position(), pair.getBreakend(false).position());

            if(genesList.isEmpty())
                continue;

            for(final EnsemblGeneData geneData : genesList)
            {
                genesStr = appendStr(genesStr, geneData.GeneName, ';');
            }
        }

        return genesStr;
    }

    private static List<Integer> CN_SEGMENT_BUCKETS = Lists.newArrayList(0, 3, 6, 10, 20, 50, 100);

    private static String getCopyNumberSegmentData(final SvCluster cluster, double samplePloidy, double minDmCopyNumber)
    {
        final long[] cnSegmentLengths = new long[CN_SEGMENT_BUCKETS.size()];

        for(List<SvBreakend> breakendList : cluster.getChrBreakendMap().values())
        {
            long prevPosition = 0;
            double prevCN = 0;
            boolean inSegment = false;
            double netPloidy = 0;

            for(SvBreakend breakend : breakendList)
            {
                if(!inSegment)
                {
                    if(breakend.orientation() == 1)
                    {
                        if(prevPosition > 0)
                        {
                            double avgCopyCN = (prevCN + breakend.copyNumber()) * 0.5;
                            long segmentLength = breakend.position() - prevPosition;
                            addCnSegmentData(cnSegmentLengths, segmentLength, samplePloidy, avgCopyCN, minDmCopyNumber);
                        }

                        continue;
                    }

                    inSegment = true;
                    prevPosition = breakend.position();
                    prevCN = breakend.copyNumber();
                    netPloidy = breakend.ploidy();
                }
                else
                {
                    long segmentLength = breakend.position() - prevPosition;

                    if(breakend.orientation() == -1)
                    {
                        // another breakend increasing CN - record segment CN up to this point
                        addCnSegmentData(cnSegmentLengths, segmentLength, samplePloidy, prevCN, minDmCopyNumber);

                        // move position on
                        prevPosition = breakend.position();
                        prevCN = breakend.copyNumber();
                        netPloidy += breakend.ploidy();
                    }
                    else
                    {
                        double avgCopyCN = (prevCN + breakend.copyNumber()) * 0.5;
                        addCnSegmentData(cnSegmentLengths, segmentLength, samplePloidy, avgCopyCN, minDmCopyNumber);

                        netPloidy -= breakend.ploidy();
                        prevPosition = breakend.position();

                        if(copyNumbersEqual(netPloidy, 0))
                            inSegment = false;
                    }
                }
            }
        }

        String cnSegmentData = "";

        for(int i = 0; i < cnSegmentLengths.length; ++i)
        {
            cnSegmentData += String.format(",%d", cnSegmentLengths[i]);
        }

        return cnSegmentData;
    }

    private static void addCnSegmentData(
            final long[] cnSegmentLengths, long length, double samplePloidy, double copyNumber, double minDmCopyNumber)
    {
        // bucket into maximum 5 multiples of sample ploidy
        double cnRatio = max(copyNumber/samplePloidy, 1);

        if(copyNumber >= minDmCopyNumber || copyNumbersEqual(copyNumber, minDmCopyNumber))
            cnSegmentLengths[0] += length;

        if(cnRatio < CN_SEGMENT_BUCKETS.get(1))
            return;

        int index = 1;

        for(; index < cnSegmentLengths.length; ++index)
        {
            if(cnRatio < CN_SEGMENT_BUCKETS.get(index))
                break;
        }

        --index;

        cnSegmentLengths[index] += length;
    }

    public void close()
    {
        closeBufferedWriter(mFileWriter);
    }
}
