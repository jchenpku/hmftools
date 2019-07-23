package com.hartwig.hmftools.linx.analysis;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.round;

import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.BND;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DEL;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DUP;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.INS;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.INV;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.SGL;
import static com.hartwig.hmftools.linx.chaining.LinkFinder.getMinTemplatedInsertionLength;
import static com.hartwig.hmftools.linx.chaining.LinkFinder.haveLinkedAssemblies;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.CHROMOSOME_ARM_P;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.CHROMOSOME_ARM_Q;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.addSvToChrBreakendMap;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.copyNumbersEqual;
import static com.hartwig.hmftools.linx.types.ResolvedType.DUP_BE;
import static com.hartwig.hmftools.linx.types.ResolvedType.LOW_VAF;
import static com.hartwig.hmftools.linx.types.ResolvedType.NONE;
import static com.hartwig.hmftools.linx.types.ResolvedType.PAIR_OTHER;
import static com.hartwig.hmftools.linx.types.ResolvedType.SGL_PAIR_DEL;
import static com.hartwig.hmftools.linx.types.ResolvedType.SGL_PAIR_DUP;
import static com.hartwig.hmftools.linx.types.ResolvedType.SGL_PAIR_INS;
import static com.hartwig.hmftools.linx.cn.LohEvent.CN_DATA_NO_SV;
import static com.hartwig.hmftools.linx.types.SvVarData.RELATION_TYPE_NEIGHBOUR;
import static com.hartwig.hmftools.linx.types.SvVarData.RELATION_TYPE_OVERLAP;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_END;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_START;
import static com.hartwig.hmftools.linx.types.SvVarData.isStart;
import static com.hartwig.hmftools.linx.types.SvaConstants.LOW_CN_CHANGE_SUPPORT;
import static com.hartwig.hmftools.linx.types.SvaConstants.MIN_DEL_LENGTH;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hartwig.hmftools.common.variant.structural.StructuralVariantType;
import com.hartwig.hmftools.linx.cn.HomLossEvent;
import com.hartwig.hmftools.linx.types.ResolvedType;
import com.hartwig.hmftools.linx.types.SvBreakend;
import com.hartwig.hmftools.linx.types.SvCluster;
import com.hartwig.hmftools.linx.cn.LohEvent;
import com.hartwig.hmftools.linx.types.SvVarData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SvClusteringMethods {

    private static final Logger LOGGER = LogManager.getLogger(SvClusteringMethods.class);

    private int mNextClusterId;

    private Map<String, List<SvBreakend>> mChrBreakendMap; // every breakend on a chromosome, ordered by ascending position
    private List<LohEvent> mLohEventList;
    private List<HomLossEvent> mHomLossList;
    private List<SvVarData> mExcludedSVs; // eg duplicate breakends

    private Map<String, double[]> mChromosomeCopyNumberMap; // p-arm telomere, centromere and q-arm telemore CN data

    private long mDelCutoffLength;
    private long mDupCutoffLength;
    private int mProximityDistance;

    public static int MAX_SIMPLE_DUP_DEL_CUTOFF = 5000000;
    public static int MIN_SIMPLE_DUP_DEL_CUTOFF = 100000;
    public static int DEFAULT_PROXIMITY_DISTANCE = 5000;

    public static String CLUSTER_REASON_PROXIMITY = "Prox";
    public static String CLUSTER_REASON_LOH = "LOH";
    public static String CLUSTER_REASON_HOM_LOSS = "HomLoss";
    public static String CLUSTER_REASON_COMMON_ARMS = "ComArm";
    public static String CLUSTER_REASON_FOLDBACKS = "Foldback";
    public static String CLUSTER_REASON_SOLO_SINGLE = "Single";
    public static String CLUSTER_REASON_LOOSE_OVERLAP = "LooseOverlap";
    public static String CLUSTER_REASON_LOH_CHAIN = "LOHChain";
    public static String CLUSTER_REASON_NET_ARM_END_PLOIDY = "ArmEndPloidy";
    public static String CLUSTER_REASON_BE_PLOIDY_DROP = "BEPloidy";
    public static String CLUSTER_REASON_LONG_DEL_DUP_OR_INV = "DelDupInv";

    public SvClusteringMethods(int proximityLength)
    {
        mNextClusterId = 0;

        mDelCutoffLength = 0;
        mDupCutoffLength = 0;
        mProximityDistance = proximityLength;

        mChrBreakendMap = new HashMap();
        mChromosomeCopyNumberMap = new HashMap();
        mExcludedSVs = Lists.newArrayList();
        mLohEventList = null;
        mHomLossList = null;
    }

    public final Map<String, List<SvBreakend>> getChrBreakendMap() { return mChrBreakendMap; }
    public int getNextClusterId() { return mNextClusterId++; }

    public void setSampleCnEventData(final List<LohEvent> lohEvents, List<HomLossEvent> homLossEvents)
    {
        mLohEventList = lohEvents;
        mHomLossList = homLossEvents;
    }

    public void setChrCopyNumberMap(final Map<String, double[]> data) { mChromosomeCopyNumberMap = data; }
    public final Map<String, double[]> getChrCopyNumberMap() { return mChromosomeCopyNumberMap; }
    public long getDupCutoffLength() { return mDelCutoffLength; }
    public long getDelCutoffLength() { return mDupCutoffLength; }
    public int getProximityDistance() { return mProximityDistance; }

    public void clusterExcludedVariants(List<SvCluster> clusters)
    {
        for(SvVarData var : mExcludedSVs)
        {
            SvCluster newCluster = new SvCluster(getNextClusterId());
            newCluster.addVariant(var);

            ResolvedType exclusionReason = NONE;
            if(var.type() != SGL)
            {
                if(var.isEquivBreakend())
                    exclusionReason = DUP_BE;
                else
                    exclusionReason = LOW_VAF;
            }
            else
            {
                exclusionReason = getSingleBreakendUnclusteredType(var);

                if (exclusionReason == NONE)
                    exclusionReason = DUP_BE;
            }

            newCluster.setResolved(true, exclusionReason);
            clusters.add(newCluster);
        }
    }

    public void clusterByProximity(List<SvCluster> clusters)
    {
        // walk through each chromosome and breakend list
        for (final Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            final List<SvBreakend> breakendList = entry.getValue();

            int currentIndex = 0;
            while (currentIndex < breakendList.size())
            {
                final SvBreakend breakend = breakendList.get(currentIndex);
                SvVarData var = breakend.getSV();

                SvBreakend nextBreakend = null;
                int nextIndex = currentIndex + 1;

                for (; nextIndex < breakendList.size(); ++nextIndex)
                {
                    final SvBreakend nextBe = breakendList.get(nextIndex);
                    nextBreakend = nextBe;
                    break;
                }

                if (nextBreakend == null)
                {
                    // no more breakends on this chromosome
                    if (var.getCluster() == null)
                    {
                        SvCluster cluster = new SvCluster(getNextClusterId());
                        cluster.addVariant(var);
                        clusters.add(cluster);
                    }

                    break;
                }

                SvCluster cluster = var.getCluster();
                SvVarData nextVar = nextBreakend.getSV();
                SvCluster nextCluster = nextVar.getCluster();

                if (cluster != null && cluster == nextCluster)
                {
                    // already clustered
                }
                else if (abs(nextBreakend.position() - breakend.position()) > mProximityDistance)
                {
                    // too far between the breakends
                    if (cluster == null)
                    {
                        cluster = new SvCluster(getNextClusterId());
                        cluster.addVariant(var);
                        clusters.add(cluster);
                    }
                    else
                    {
                        // nothing more to do for this variant - already clustered
                    }
                }
                else
                {
                    // 2 breakends are close enough to cluster
                    if (var == nextVar)
                    {
                        if (cluster == null)
                        {
                            cluster = new SvCluster(getNextClusterId());
                            cluster.addVariant(var);
                            clusters.add(cluster);
                        }
                    }
                    else
                    {
                        // one or both SVs could already be a part of clusters, or neither may be
                        if (cluster == null && nextCluster == null)
                        {
                            cluster = new SvCluster(getNextClusterId());
                            cluster.addVariant(var);
                            cluster.addVariant(nextVar);
                            cluster.addClusterReason(CLUSTER_REASON_PROXIMITY);
                            clusters.add(cluster);
                        }
                        else if (cluster != null && nextCluster != null)
                        {
                            // keep one and remove the other
                            cluster.mergeOtherCluster(nextCluster, false);
                            cluster.addClusterReason(CLUSTER_REASON_PROXIMITY);
                            clusters.remove(nextCluster);
                        }
                        else
                        {
                            if (cluster == null)
                            {
                                nextCluster.addVariant(var);
                                nextCluster.addClusterReason(CLUSTER_REASON_PROXIMITY);
                            }
                            else
                            {
                                cluster.addVariant(nextVar);
                                cluster.addClusterReason(CLUSTER_REASON_PROXIMITY);
                            }
                        }

                        if (var.getClusterReason().isEmpty())
                            var.addClusterReason(CLUSTER_REASON_PROXIMITY, nextVar.id());

                        if (nextVar.getClusterReason().isEmpty())
                            nextVar.addClusterReason(CLUSTER_REASON_PROXIMITY, var.id());

                        // checkClusteringClonalDiscrepancy(var, nextVar, CLUSTER_REASON_PROXIMITY);
                    }
                }

                // move the index to the SV which was just proximity cluster so the next comparison is with the closest candidate
                currentIndex = nextIndex;
            }
        }
    }

    public static void addClusterReasons(final SvVarData var1, final SvVarData var2, final String clusterReason)
    {
        var1.addClusterReason(clusterReason, var2.id());
        var2.addClusterReason(clusterReason, var1.id());
        // checkClusteringClonalDiscrepancy(var1, var2, clusterReason);
    }

    public void mergeClusters(final String sampleId, List<SvCluster> clusters)
    {
        // first apply replication rules since this can affect consistency
        for(SvCluster cluster : clusters)
        {
            if(cluster.isResolved())
                continue;

            markClusterLongDelDups(cluster);
            markClusterInversions(cluster);
        }

        associateBreakendCnEvents(sampleId, clusters);

        int initClusterCount = clusters.size();

        int iterations = 0;

        // the merge must be run a few times since as clusters grow, more single SVs and other clusters
        // will then fall within the bounds of the new larger clusters
        boolean foundMerges = true;

        while(foundMerges && iterations < 5)
        {
            if(mergeOnOverlappingInvDupDels(clusters))
                foundMerges = true;

            if(mergeOnUnresolvedSingles(clusters))
                foundMerges = true;

            ++iterations;
        }

        mergeOnLOHEvents(clusters);

        if(clusters.size() < initClusterCount)
        {
            LOGGER.debug("reduced cluster count({} -> {}) iterations({})", initClusterCount, clusters.size(), iterations);
        }
    }

    public static boolean hasLowCNChangeSupport(final SvVarData var)
    {
        if(var.type() == INS)
            return false;

        if(var.isNullBreakend())
            return var.copyNumberChange(true) < LOW_CN_CHANGE_SUPPORT;
        else
            return var.copyNumberChange(true) < LOW_CN_CHANGE_SUPPORT && var.copyNumberChange(false) < LOW_CN_CHANGE_SUPPORT;
    }

    private ResolvedType getSingleBreakendUnclusteredType(final SvVarData var)
    {
        if(var.type() != SGL|| var.isNoneSegment())
            return NONE;

        if(var.isEquivBreakend())
            return DUP_BE;

        return NONE;
    }

    public void clearLOHBreakendData(final String sampleId)
    {
        if(mLohEventList != null)
        {
            mLohEventList.forEach(x -> x.clearBreakends());
        }

        if(mHomLossList != null)
        {
            mHomLossList.forEach(x -> x.clearBreakends());
        }
    }

    private void associateBreakendCnEvents(final String sampleId, List<SvCluster> clusters)
    {
        // search for breakends that match LOH and Hom-loss events
        // note that LOH-breakend links are established here and then must be tidied up once the sample is complete

        String currentChromosome = "";
        List<SvBreakend> breakendList = null;

        int missedEvents = 0;

        if(mLohEventList != null && !mLohEventList.isEmpty())
        {
            for (final LohEvent lohEvent : mLohEventList)
            {
                if (!lohEvent.isSvEvent())
                    continue;

                // use the breakend table to find matching SVs
                if (breakendList == null || !currentChromosome.equals(lohEvent.Chromosome))
                {
                    breakendList = mChrBreakendMap.get(lohEvent.Chromosome);
                    currentChromosome = lohEvent.Chromosome;
                }

                if (breakendList == null)
                    continue;

                for (final SvBreakend breakend : breakendList)
                {
                    if (breakend.orientation() == 1 && breakend.getSV().dbId() == lohEvent.StartSV)
                    {
                        lohEvent.setBreakend(breakend, true);
                        breakend.getCluster().addLohEvent(lohEvent);
                    }

                    if (breakend.orientation() == -1 && breakend.getSV().dbId() == lohEvent.EndSV)
                    {
                        lohEvent.setBreakend(breakend, false);
                        breakend.getCluster().addLohEvent(lohEvent);
                    }

                    if (lohEvent.matchedBothSVs())
                        break;
                }

                if (lohEvent.StartSV != CN_DATA_NO_SV && lohEvent.getBreakend(true) == null)
                    ++missedEvents;

                if (lohEvent.EndSV != CN_DATA_NO_SV && lohEvent.getBreakend(false) == null)
                    ++missedEvents;
            }
        }

        if(mHomLossList != null && !mHomLossList.isEmpty())
        {
            for (HomLossEvent homLossEvent : mHomLossList)
            {
                if (homLossEvent.StartSV == CN_DATA_NO_SV && homLossEvent.EndSV == CN_DATA_NO_SV)
                    continue;

                breakendList = mChrBreakendMap.get(homLossEvent.Chromosome);

                if (breakendList == null)
                    continue;

                for (final SvBreakend breakend : breakendList)
                {
                    if (breakend.orientation() == 1 && breakend.getSV().dbId() == homLossEvent.StartSV)
                    {
                        homLossEvent.setBreakend(breakend, true);
                        // breakend.getCluster().addHomLossEvent(homLossEvent);
                    }

                    if (breakend.orientation() == -1 && breakend.getSV().dbId() == homLossEvent.EndSV)
                    {
                        homLossEvent.setBreakend(breakend, false);
                        // breakend.getCluster().addHomLossEvent(homLossEvent);
                    }

                    if (homLossEvent.matchedBothSVs())
                        break;
                }

                if (homLossEvent.StartSV != CN_DATA_NO_SV && homLossEvent.getBreakend(true) == null)
                    ++missedEvents;

                if (homLossEvent.EndSV != CN_DATA_NO_SV && homLossEvent.getBreakend(false) == null)
                    ++missedEvents;
            }
        }

        if(missedEvents > 0)
        {
            LOGGER.warn("sample({}) missed {} links to LOH and hom-loss events", sampleId, missedEvents);
        }
    }

    private void mergeOnLOHEvents(List<SvCluster> clusters)
    {
        if (mLohEventList.isEmpty() && mHomLossList.isEmpty())
            return;

        // first link up breakends joined by an LOH with no multi-SV hom-loss events within
        for (final LohEvent lohEvent : mLohEventList)
        {
            if (!lohEvent.matchedBothSVs() || lohEvent.hasIncompleteHomLossEvents())
                continue; // cannot be used for clustering

            SvBreakend lohBeStart = lohEvent.getBreakend(true);
            SvCluster lohClusterStart = lohBeStart.getCluster();
            SvVarData lohSvStart = lohBeStart.getSV();
            SvBreakend lohBeEnd = lohEvent.getBreakend(false);
            SvCluster lohClusterEnd = lohBeEnd.getCluster();
            SvVarData lohSvEnd = lohBeEnd.getSV();

            if (lohClusterStart != lohClusterEnd)
            {
                if (!lohClusterStart.hasLinkingLineElements() && !lohClusterEnd.hasLinkingLineElements())
                {
                    LOGGER.debug("cluster({} svs={}) merges in other cluster({} svs={}) on LOH event: SVs({} and {}) length({})",
                            lohClusterStart.id(), lohClusterStart.getSvCount(), lohClusterEnd.id(), lohClusterEnd.getSvCount(),
                            lohEvent.StartSV, lohEvent.EndSV, lohEvent.Length);

                    addClusterReasons(lohSvStart, lohSvEnd, CLUSTER_REASON_LOH);
                    lohClusterStart.addClusterReason(CLUSTER_REASON_LOH);

                    lohClusterStart.mergeOtherCluster(lohClusterEnd);
                    clusters.remove(lohClusterEnd);
                }
            }
        }

        // search for LOH events which are clustered but which contain hom-loss events which aren't clustered
        // (other than simple DELs) which already will have been handled
        for (final LohEvent lohEvent : mLohEventList)
        {
            if (!lohEvent.hasIncompleteHomLossEvents())
                continue;

            // already clustered - search for hom-loss events contained within this LOH that isn't clustered
            if(lohEvent.clustered() || lohEvent.wholeArmLoss())
            {
                List<HomLossEvent> unclusteredHomLossEvents = lohEvent.getHomLossEvents().stream()
                        .filter(HomLossEvent::matchedBothSVs)
                        .filter(x -> !x.sameSV())
                        .filter(x -> x.PosStart > lohEvent.PosStart)
                        .filter(x -> x.PosEnd < lohEvent.PosEnd)
                        .filter(x -> !x.clustered())
                        .collect(Collectors.toList());

                for (final HomLossEvent homLoss : unclusteredHomLossEvents)
                {
                    SvBreakend homLossBeStart = homLoss.getBreakend(true);
                    SvBreakend homLossBeEnd = homLoss.getBreakend(false);

                    SvCluster cluster = homLossBeStart.getCluster();
                    SvCluster otherCluster = homLossBeEnd.getCluster();

                    if(cluster == otherCluster) // protect against clusters already merged or removed
                        continue;

                    LOGGER.debug("cluster({} svs={}) merges in other cluster({} svs={}) on hom-loss({}: {} -> {}) inside LOH event({} -> {})",
                            cluster.id(), cluster.getSvCount(), otherCluster.id(), otherCluster.getSvCount(),
                            homLoss.Chromosome, homLoss.PosStart, homLoss.PosEnd, lohEvent.PosStart, lohEvent.PosEnd);

                    addClusterReasons(homLossBeStart.getSV(), homLossBeEnd.getSV(), CLUSTER_REASON_HOM_LOSS);
                    cluster.addClusterReason(CLUSTER_REASON_HOM_LOSS);

                    cluster.mergeOtherCluster(otherCluster);
                    clusters.remove(otherCluster);
               }

                continue;
            }

            if (!lohEvent.matchedBothSVs() || lohEvent.clustered())
                continue;

            if (lohEvent.getBreakend(true).getCluster().hasLinkingLineElements()
            || lohEvent.getBreakend(false).getCluster().hasLinkingLineElements())
            {
                continue;
            }

            // now look for an LOH with unclustered breakends but which contains only clustered hom-loss events
            boolean hasIncompleteHomLossEvent = false;

            for(final HomLossEvent homLossEvent : lohEvent.getHomLossEvents())
            {
                if(!(homLossEvent.PosStart > lohEvent.PosStart && homLossEvent.PosEnd < lohEvent.PosEnd))
                {
                    // handle overlapping separately
                    hasIncompleteHomLossEvent = true;
                    break;
                }

                if(!homLossEvent.matchedBothSVs())
                {
                    hasIncompleteHomLossEvent = true;
                    break;
                }

                if(!homLossEvent.clustered())
                {
                    hasIncompleteHomLossEvent = true;
                    break;
                }
            }

            if(!hasIncompleteHomLossEvent)
            {
                // all hom-loss events involving more than 1 SV were clustered, so clustered the LOH SVs
                SvBreakend lohBeStart = lohEvent.getBreakend(true);
                SvBreakend lohBeEnd = lohEvent.getBreakend(false);

                SvCluster cluster = lohBeStart.getCluster();
                SvCluster otherCluster = lohBeEnd.getCluster();

                lohEvent.setIsValid(true);

                LOGGER.debug("cluster({} svs={}) merges in other cluster({} svs={}) on no unclustered hom-loss events",
                        cluster.id(), cluster.getSvCount(), otherCluster.id(), otherCluster.getSvCount());

                addClusterReasons(lohBeStart.getSV(), lohBeEnd.getSV(), CLUSTER_REASON_HOM_LOSS);
                cluster.addClusterReason(CLUSTER_REASON_HOM_LOSS);

                cluster.mergeOtherCluster(otherCluster);
                clusters.remove(otherCluster);
            }

            // finally look for overlapping LOH and hom-loss events where all but 2 of the breakends are clustered
            // resulting in a clustering of a breakend from the LOH with one of the hom-loss breakends

            List<SvBreakend> unclusteredBreakends = Lists.newArrayList();
            boolean allBreakendsValid = true;

            for(int se = SE_START; se <= SE_END; ++se)
            {
                boolean isStart = isStart(se);
                unclusteredBreakends.add(lohEvent.getBreakend(isStart));

                for(final HomLossEvent homLossEvent : lohEvent.getHomLossEvents())
                {
                    if(!homLossEvent.matchedBothSVs())
                    {
                        allBreakendsValid = false;
                        break;
                    }

                    unclusteredBreakends.add(homLossEvent.getBreakend(isStart));
                }

                if(!allBreakendsValid)
                    break;
            }

            if(allBreakendsValid)
            {
                int i = 0;
                while(i < unclusteredBreakends.size())
                {
                    final SvBreakend be1 = unclusteredBreakends.get(i);

                    boolean found = false;
                    for(int j = i+1; j < unclusteredBreakends.size(); ++j)
                    {
                        final SvBreakend be2 = unclusteredBreakends.get(j);

                        if(be1.getCluster() == be2.getCluster())
                        {
                            unclusteredBreakends.remove(j);
                            unclusteredBreakends.remove(i);
                            found = true;
                            break;
                        }
                    }

                    if(!found)
                        ++i;
                }
            }

            if(unclusteredBreakends.size() == 2)
            {
                SvBreakend breakend1 = unclusteredBreakends.get(0);
                SvBreakend breakend2 = unclusteredBreakends.get(1);
                SvCluster cluster = breakend1.getCluster();
                SvCluster otherCluster = breakend2.getCluster();

                lohEvent.setIsValid(true);

                LOGGER.debug("cluster({} svs={}) merges in other cluster({} svs={}) on unclustered LOH and hom-loss breakends",
                        cluster.id(), cluster.getSvCount(), otherCluster.id(), otherCluster.getSvCount());

                addClusterReasons(breakend1.getSV(), breakend2.getSV(), CLUSTER_REASON_HOM_LOSS);
                cluster.addClusterReason(CLUSTER_REASON_HOM_LOSS);

                cluster.mergeOtherCluster(otherCluster);
                clusters.remove(otherCluster);
            }
        }
    }

    private void markClusterInversions(final SvCluster cluster)
    {
        if(cluster.getTypeCount(INV) == 0)
            return;

        // skip cluster-2s which resolved to a simple type and not long
        if(cluster.isResolved() && cluster.getResolvedType().isSimple())
            return;

        for (final SvVarData var : cluster.getSVs())
        {
            if(var.type() == INV && !var.isCrossArm())
            {
                cluster.registerInversion(var);
            }
        }
    }

    private void markClusterLongDelDups(final SvCluster cluster)
    {
        // find and record any long DEL or DUP for merging, including long synthetic ones
        if(cluster.isSyntheticType() && cluster.getResolvedType().isSimple() && !cluster.isResolved())
        {
            if ((cluster.getResolvedType() == ResolvedType.DEL && cluster.getSyntheticLength() >= mDelCutoffLength)
            || (cluster.getResolvedType() == ResolvedType.DUP && cluster.getSyntheticLength() >= mDupCutoffLength))
            {
                for (final SvVarData var : cluster.getSVs())
                {
                    cluster.registerLongDelDup(var);
                }
            }
        }

        if(cluster.getTypeCount(DEL) > 0 || cluster.getTypeCount(DUP) > 0)
        {
            for(final SvVarData var : cluster.getSVs())
            {
                if(var.isCrossArm())
                    continue;

                if(exceedsDupDelCutoffLength(var.type(), var.length()))
                {
                    cluster.registerLongDelDup(var);
                }
            }
        }
    }

    private boolean mergeOnOverlappingInvDupDels(List<SvCluster> clusters)
    {
        // merge any clusters with overlapping inversions, long dels or long dups on the same arm
        int initClusterCount = clusters.size();

        final List<StructuralVariantType> requiredTypes = Lists.newArrayList();
        requiredTypes.add(INV);

        int index1 = 0;
        while(index1 < clusters.size())
        {
            SvCluster cluster1 = clusters.get(index1);

            if(cluster1.getInversions().isEmpty() && cluster1.getLongDelDups().isEmpty() || cluster1.hasLinkingLineElements())
            {
                ++index1;
                continue;
            }

            List<SvVarData> cluster1Svs = Lists.newArrayList();
            cluster1Svs.addAll(cluster1.getLongDelDups());
            cluster1Svs.addAll(cluster1.getInversions());

            int index2 = index1 + 1;
            while(index2 < clusters.size())
            {
                SvCluster cluster2 = clusters.get(index2);

                if(cluster2.getInversions().isEmpty() && cluster2.getLongDelDups().isEmpty() || cluster2.hasLinkingLineElements())
                {
                    ++index2;
                    continue;
                }

                List<SvVarData> cluster2Svs = Lists.newArrayList();
                cluster2Svs.addAll(cluster2.getLongDelDups());
                cluster2Svs.addAll(cluster2.getInversions());

                boolean canMergeClusters = false;
                boolean conflictingEvents = false;

                for (final SvVarData var1 : cluster1Svs)
                {
                    for (final SvVarData var2 : cluster2Svs)
                    {
                        if(!var1.chromosome(true).equals(var2.chromosome(true)))
                            continue;

                        if(var1.position(false) < var2.position(true) || var1.position(true) > var2.position(false))
                            continue;

                        // check for conflicting LOH / hom-loss events
                        for(int se1 = SE_START; se1 <= SE_END; ++se1)
                        {
                            final SvBreakend be1 = var1.getBreakend(isStart(se1));

                            for(int se2 = SE_START; se2 <= SE_END; ++se2)
                            {
                                final SvBreakend be2 = var2.getBreakend(isStart(se2));

                                if(!be1.chromosome().equals(be2.chromosome()))
                                    continue;

                                if(breakendsInLohAndHomLossEvents(be1, be2) || breakendsInLohAndHomLossEvents(be2, be1))
                                {
                                    LOGGER.debug("cluster({}) SV({} {}) and cluster({}) SV({} {}) have longDDI overlap but conflicting LOH & hom-loss events",
                                            cluster1.id(), var1.posId(), var1.type(), cluster2.id(), var2.posId(), var2.type());

                                    conflictingEvents = true;
                                    break;
                                }
                            }

                            if(conflictingEvents)
                                break;
                        }

                        if(conflictingEvents)
                            break;

                        LOGGER.debug("cluster({}) SV({} {}) and cluster({}) SV({} {}) have inversion or longDelDup overlap",
                                cluster1.id(), var1.posId(), var1.type(), cluster2.id(), var2.posId(), var2.type());

                        addClusterReasons(var1, var2, CLUSTER_REASON_LONG_DEL_DUP_OR_INV);

                        canMergeClusters = true;
                        break;
                    }

                    if(canMergeClusters)
                        break;
                }

                if(canMergeClusters)
                {
                    cluster1.mergeOtherCluster(cluster2);
                    cluster1.addClusterReason(CLUSTER_REASON_LONG_DEL_DUP_OR_INV);
                    clusters.remove(index2);
                }
                else
                {
                    ++index2;
                }
            }

            ++index1;
        }

        return clusters.size() < initClusterCount;
    }

    public boolean breakendsInLohAndHomLossEvents(final SvBreakend lohBreakend, final SvBreakend homLossBreakend)
    {
        for(final LohEvent lohEvent : lohBreakend.getCluster().getLohEvents())
        {
            boolean hasRelatedHomLoss = lohEvent.getHomLossEvents().stream()
                    .anyMatch(x -> x.getBreakend(true) == homLossBreakend || x.getBreakend(false) == homLossBreakend);

            if(hasRelatedHomLoss)
                return true;
        }

        return false;
    }

    public boolean exceedsDupDelCutoffLength(StructuralVariantType type, long length)
    {
        if(type == DEL)
            return length > mDelCutoffLength;
        else if(type == DUP)
            return length > mDupCutoffLength;
        else
            return false;
    }

    private boolean mergeOnUnresolvedSingles(List<SvCluster> clusters)
    {
        // merge clusters with 1 unresolved single with the following rules:
        // 2 x cluster-1s with SGLs that are each other's nearest neighbours
        //
        // use the chr-breakend map to walk through and find the closest links
        // only apply a rule between the 2 closest breakends at the exclusions of the cluster on their other end
        // unless the other breakend is a short, simple SV

        boolean foundMerges = false;

        for (final Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            final List<SvBreakend> breakendList = entry.getValue();
            int breakendCount = breakendList.size();

            for(int i = 0; i < breakendList.size(); ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                SvVarData var = breakend.getSV();
                final SvCluster cluster = var.getCluster();

                // take the point of view of the cluster with the solo single
                if(cluster.getSvCount() != 1 || cluster.getSV(0).type() != SGL || cluster.isResolved())
                    continue;

                // now look for a proximate cluster with either another solo single or needing one to be resolved
                // check previous and next breakend's cluster
                SvBreakend prevBreakend = (i > 0) ? breakendList.get(i - 1) : null;
                SvBreakend nextBreakend = (i < breakendCount - 1) ? breakendList.get(i + 1) : null;

                if(prevBreakend != null && prevBreakend.getSV().isSimpleType()
                && !exceedsDupDelCutoffLength(prevBreakend.getSV().type(), prevBreakend.getSV().length()))
                {
                    prevBreakend = null;
                }

                if(nextBreakend != null && nextBreakend.getSV().isSimpleType()
                && !exceedsDupDelCutoffLength(nextBreakend.getSV().type(), nextBreakend.getSV().length()))
                {
                    nextBreakend = null;
                }

                // additionally check that breakend after the next one isn't a closer SGL to the next breakend,
                // which would invalidate this one being the nearest neighbour
                long prevProximity = prevBreakend != null ? abs(breakend.position() - prevBreakend.position()) : -1;
                long nextProximity = nextBreakend != null ? abs(breakend.position() - nextBreakend.position()) : -1;

                if(nextBreakend != null && i < breakendCount - 2)
                {
                    final SvBreakend followingBreakend = breakendList.get(i + 2);
                    final SvCluster followingCluster = followingBreakend.getCluster();

                    if(followingCluster.getSvCount() == 1 && followingBreakend.getSV().type() == SGL && !followingCluster.isResolved())
                    {
                        long followingProximity = abs(nextBreakend.position() - followingBreakend.position());

                        if (followingProximity < nextProximity)
                            nextBreakend = null;
                    }
                }

                if(nextBreakend == null && prevBreakend == null)
                    continue;

                SvBreakend otherBreakend = null;

                if(nextBreakend != null && prevBreakend != null)
                {
                    otherBreakend = nextProximity < prevProximity ? nextBreakend : prevBreakend;
                }
                else if(nextBreakend != null)
                {
                    otherBreakend = nextBreakend;
                }
                else
                {
                    otherBreakend = prevBreakend;
                }

                SvVarData otherVar = otherBreakend.getSV();
                final SvCluster otherCluster = otherVar.getCluster();

                ResolvedType resolvedType = canResolveWithSoloSingle(otherCluster, cluster);

                if(resolvedType != NONE)
                {
                    otherCluster.mergeOtherCluster(cluster);
                    otherCluster.addClusterReason(CLUSTER_REASON_SOLO_SINGLE);
                    otherCluster.setResolved(true, resolvedType);
                    otherCluster.setSyntheticData(abs(otherVar.position(true) - var.position(true)), 0);

                    clusters.remove(cluster);
                    foundMerges = true;
                    break;
                }
            }
        }

        return foundMerges;
    }

    private final ResolvedType canResolveWithSoloSingle(SvCluster otherCluster, SvCluster soloSingleCluster)
    {
        // 3 cases:
        // - 2 x SGLs could form a simple DEL or DUP
        // - 2 x SGLs + another SV could form a simple cluster-2 resolved type
        // - a SGL + another SV could form a simple cluster-2 resolved type

        final SvVarData soloSingle = soloSingleCluster.getSV(0);

        if(otherCluster.getSvCount() == 1)
        {
            final SvVarData otherVar = otherCluster.getSV(0);

            if(otherVar.type() == SGL)
            {
                // either both must be NONEs or one be a SGL but without centromeric or telomeric support
                if(otherCluster.isResolved())
                    return NONE;

                ResolvedType resolvedType = markSinglePairResolvedType(otherVar, soloSingle);

                if(resolvedType == NONE)
                    return NONE;

                LOGGER.debug("cluster({}) SV({}) and cluster({}) SV({}) syntheticType({})",
                        soloSingleCluster.id(), soloSingle.posId(), otherCluster.id(), otherVar.posId(), resolvedType);

                addClusterReasons(soloSingle, otherVar, CLUSTER_REASON_SOLO_SINGLE);

                return resolvedType;
            }
            else
            {
                boolean inconsistentOnStart;
                if(otherVar.hasInconsistentCopyNumberChange(true) && otherVar.chromosome(false).equals(soloSingle.chromosome(true)))
                {
                    inconsistentOnStart = true;
                }
                else if(otherVar.hasInconsistentCopyNumberChange(false) && otherVar.chromosome(true).equals(soloSingle.chromosome(true)))
                {
                    inconsistentOnStart = false;
                }
                else
                {
                    return NONE;
                }

                double cnInconsistency = otherVar.ploidy() - otherVar.copyNumberChange(inconsistentOnStart);

                if(round(cnInconsistency) != round(soloSingle.copyNumberChange(true)))
                    return NONE;

                LOGGER.debug(String.format("cluster(%s) SV(%s) and cluster(%s) SV(%s) potentially resolve CN inconsistency(%.2f vs %.2f)",
                        soloSingleCluster.id(), soloSingle.posId(), otherCluster.id(), otherVar.posId(),
                        cnInconsistency, soloSingle.copyNumberChange(true)));

                addClusterReasons(soloSingle, otherVar, CLUSTER_REASON_SOLO_SINGLE);

                return PAIR_OTHER;
            }
        }
        else
        {
            return NONE;
        }
    }

    public static ResolvedType markSinglePairResolvedType(final SvVarData sgl1, final SvVarData sgl2)
    {
        if(sgl1.sglToCentromereOrTelomere() || sgl2.sglToCentromereOrTelomere())
            return NONE;

        final SvBreakend breakend1 = sgl1.getBreakend(true);
        final SvBreakend breakend2 = sgl2.getBreakend(true);

        // to form a simple del or dup, they need to have different orientations
        if(breakend1.orientation() == breakend2.orientation())
            return NONE;

        // check copy number consistency
        double cn1 = sgl2.copyNumberChange(true);
        double cn2 = sgl1.copyNumberChange(true);

        if(!copyNumbersEqual(cn1, cn2))
            return NONE;

        boolean breakendsFace = (breakend1.position() < breakend2.position() && breakend1.orientation() == -1)
                || (breakend2.position() < breakend1.position() && breakend2.orientation() == -1);

        long length = abs(breakend1.position() - breakend2.position());

        ResolvedType resolvedType = NONE;

        if(breakendsFace)
        {
            // a DUP if breakends are further than the anchor distance away, else an INS
            int minTiLength = getMinTemplatedInsertionLength(breakend1, breakend2);
            if(length >= minTiLength)
                resolvedType = SGL_PAIR_DUP;
            else
                resolvedType = SGL_PAIR_INS;
        }
        else
        {
            // a DEL if the breakends are further than the min DEL length, else an INS
            if(length >= MIN_DEL_LENGTH)
                resolvedType = SGL_PAIR_DEL;
            else
                resolvedType = SGL_PAIR_INS;
        }

        // mark these differently from those formed from normal SVs
        return resolvedType;
    }

    private static int DEL_DUP_LENGTH_TRIM_COUNT = 5;
    private static int MAX_ARM_COUNT = 41; // excluding the 5 short arms

    public void setSimpleVariantLengths(final String sampleId)
    {
        mDelCutoffLength = 0;
        mDupCutoffLength = 0;

        List<Long> delLengthsList = Lists.newArrayList();
        List<Long> dupLengthsList = Lists.newArrayList();

        int simpleArmCount = 0;

        for (final Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            final String chromosome = entry.getKey();
            final List<SvBreakend> breakendList = entry.getValue();

            // first check for complex events on the arm since these will be skipped
            boolean pArmHasInversions = false;
            boolean qArmHasInversions = false;

            for(final SvBreakend breakend : breakendList)
            {
                final SvVarData var = breakend.getSV();

                if(var.type() != INV)
                    continue;

                if(!pArmHasInversions && (var.arm(true) == CHROMOSOME_ARM_P || var.arm(false) == CHROMOSOME_ARM_P))
                    pArmHasInversions = true;

                if(!qArmHasInversions && (var.arm(true) == CHROMOSOME_ARM_Q || var.arm(false) == CHROMOSOME_ARM_Q))
                    qArmHasInversions = true;

                if(pArmHasInversions && qArmHasInversions)
                    break;
            }

            // skip chromosome altogether
            if(pArmHasInversions && qArmHasInversions)
                continue;

            if(!pArmHasInversions)
                ++simpleArmCount;

            if(!qArmHasInversions)
                ++simpleArmCount;

            for(final SvBreakend breakend : breakendList)
            {
                if(!breakend.usesStart() || !(breakend.getSV().type() == DEL || breakend.getSV().type() == DUP))
                    continue;

                final SvVarData var = breakend.getSV();

                if(pArmHasInversions)
                {
                    if (var.arm(true) == CHROMOSOME_ARM_P || var.arm(false) == CHROMOSOME_ARM_P)
                        continue;
                }

                if(qArmHasInversions)
                {
                    if (var.arm(true) == CHROMOSOME_ARM_Q || var.arm(false) == CHROMOSOME_ARM_Q)
                        continue;
                }

                if(var.type() == DEL)
                    delLengthsList.add(var.length());
                else if(var.type() == DUP)
                    dupLengthsList.add(var.length());
            }

            // LOGGER.debug("sample({}) chr({}) svCount({} delDups({})", sampleId, chromosome, breakendList.size(), armCount);
        }

        int trimCount = (int)round(simpleArmCount / (double)MAX_ARM_COUNT * DEL_DUP_LENGTH_TRIM_COUNT);

        if(delLengthsList.size() > trimCount)
        {
            Collections.sort(delLengthsList);
            int lengthIndex = delLengthsList.size() - trimCount - 1; // 10 items, index 0 - 9, exclude 5 - 9, select 9
            mDelCutoffLength = delLengthsList.get(lengthIndex);
        }

        if(dupLengthsList.size() > trimCount)
        {
            Collections.sort(dupLengthsList);
            int lengthIndex = dupLengthsList.size() - trimCount - 1;
            mDupCutoffLength = dupLengthsList.get(lengthIndex);
        }

        mDelCutoffLength = min(max(mDelCutoffLength, MIN_SIMPLE_DUP_DEL_CUTOFF), MAX_SIMPLE_DUP_DEL_CUTOFF);
        mDupCutoffLength = min(max(mDupCutoffLength, MIN_SIMPLE_DUP_DEL_CUTOFF), MAX_SIMPLE_DUP_DEL_CUTOFF);

        LOGGER.debug("sample({}) simple dels count({}) cutoff-length({}), dups count({}) cutoff-length({}) simpleArms({}) trimCount({})",
                sampleId, delLengthsList.size(), mDelCutoffLength, dupLengthsList.size(), mDupCutoffLength, simpleArmCount, trimCount);
    }

    public void populateChromosomeBreakendMap(final List<SvVarData> allVariants)
    {
        mNextClusterId = 0;

        mChrBreakendMap.clear();
        mExcludedSVs.clear();

        // add each SV's breakends to a map keyed by chromosome, with the breakends in order of position lowest to highest
        for (final SvVarData var : allVariants)
        {
            addSvToChrBreakendMap(var, mChrBreakendMap);
        }

        // set indices
        for(List<SvBreakend> breakendList : mChrBreakendMap.values())
        {
            for (int i = 0; i < breakendList.size(); ++i)
            {
                breakendList.get(i).setChrPosIndex(i);
            }
        }

        filterExcludedBreakends();
    }

    private static final double LOW_VAF_THRESHOLD = 0.2;
    private static final int SHORT_INV_DISTANCE = 100;
    private static final int ISOLATED_BND_DISTANCE = 5000;

    public static final int PERMITED_SGL_DUP_BE_DISTANCE = 1;
    public static final int PERMITED_DUP_BE_DISTANCE = 35;

    private boolean isIsolatedLowVafBnd(final SvVarData var)
    {
        if(!hasLowCNChangeSupport(var))
            return false;

        for(int se = SE_START; se <= SE_END; ++se)
        {
            final SvBreakend breakend = var.getBreakend(isStart(se));
            final List<SvBreakend> breakendList = mChrBreakendMap.get(breakend.chromosome());

            int i = breakend.getChrPosIndex();

            boolean isolatedDown = (i == 0) ? true
                    : breakend.position() - breakendList.get(i - 1).position() >= ISOLATED_BND_DISTANCE;

            boolean isolatedUp = (i >= breakendList.size() - 1) ? true
                    : breakendList.get(i + 1).position() - breakend.position() >= ISOLATED_BND_DISTANCE;

            if(!isolatedDown || !isolatedUp)
                return false;

        }

        return true;
    }

    private boolean isLowVafInversion(final SvBreakend breakend, final SvBreakend nextBreakend)
    {
        final SvVarData var = breakend.getSV();

        if(nextBreakend.getSV() != var || nextBreakend.position() - breakend.position() > SHORT_INV_DISTANCE)
            return false;

        return hasLowCNChangeSupport(var) || var.calcVaf(true) < LOW_VAF_THRESHOLD;
    }

    private void filterExcludedBreakends()
    {
        // filter out duplicate breakends and low-CN change support INVs and BNDs
        // breakends aren't actually removed until all chromosomes have been processed so that the indices can be preserved for various tests
        Map<String,List<SvBreakend>> breakendRemovalMap = Maps.newHashMap();

        for(Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            final String chromosome = entry.getKey();
            List<SvBreakend> breakendList = entry.getValue();

            int breakendCount = breakendList.size();

            List<SvBreakend> removalList = breakendRemovalMap.get(chromosome);

            if(removalList == null)
            {
                removalList = Lists.newArrayList();
                breakendRemovalMap.put(chromosome, removalList);
            }

            for(int i = 0; i < breakendCount; ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                final SvVarData var = breakend.getSV();

                if(var.isNoneSegment())
                    continue;

                // first check for SGLs already marked for removal
                if(var.type() == SGL && getSingleBreakendUnclusteredType(breakendList.get(i).getSV()) != NONE)
                {
                    if(!mExcludedSVs.contains(var))
                    {
                        mExcludedSVs.add(var);
                        removalList.add(breakend);
                    }
                    continue;
                }

                if(var.type() == BND && isIsolatedLowVafBnd(var))
                {
                    LOGGER.debug("SV({}) filtered low VAF isolated BND", var.id());
                    removalList.add(breakend);
                    removeRemoteBreakend(breakend.getOtherBreakend(), breakendRemovalMap);
                    mExcludedSVs.add(var);
                    continue;
                }

                if(i >= breakendCount - 1)
                    break;

                SvBreakend nextBreakend = breakendList.get(i + 1);

                if(var.type() == INV && isLowVafInversion(breakend, nextBreakend))
                {
                    LOGGER.debug("SV({}) filtered low VAF / CN change INV", var.id());
                    removalList.add(breakend);
                    removalList.add(nextBreakend);
                    mExcludedSVs.add(var);
                    continue;
                }

                if(nextBreakend.getSV() == var)
                    continue;

                SvVarData nextVar = nextBreakend.getSV();

                long distance = nextBreakend.position() - breakend.position();

                if(distance > PERMITED_DUP_BE_DISTANCE || breakend.orientation() != nextBreakend.orientation())
                    continue;

                if(var.type() == SGL || nextVar.type() == SGL)
                {
                    if(distance <= PERMITED_SGL_DUP_BE_DISTANCE)
                    {
                        if(var.type() == SGL)
                        {
                            mExcludedSVs.add(var);
                            removalList.add(breakend);
                        }

                        if(nextVar.type() == SGL)
                        {
                            mExcludedSVs.add(nextVar);
                            removalList.add(nextBreakend);

                        }
                    }
                }
                else if(var.type() == nextVar.type() && var.isEquivBreakend())
                {
                    // 2 non-SGL SVs may be duplicates, so check their other ends
                    SvBreakend otherBe = breakend.getOtherBreakend();
                    SvBreakend nextOtherBe = nextBreakend.getOtherBreakend();

                    if(otherBe.chromosome().equals(nextOtherBe.chromosome())
                    && abs(otherBe.position() - nextOtherBe.position()) <= PERMITED_DUP_BE_DISTANCE)
                    {
                        // remove both of the duplicates breakends now

                        // select the one with assembly if only has as them
                        if((var.getTIAssemblies(true).isEmpty() && !nextVar.getTIAssemblies(true).isEmpty())
                        || (var.getTIAssemblies(false).isEmpty() && !nextVar.getTIAssemblies(false).isEmpty()))
                        {
                            mExcludedSVs.add(var);
                            removalList.add(breakend);

                            if(breakend.chromosome().equals(otherBe.chromosome()))
                            {
                                removalList.add(otherBe);
                            }
                            else
                            {
                                removeRemoteBreakend(otherBe, breakendRemovalMap);
                            }
                        }
                        else
                        {
                            mExcludedSVs.add(nextVar);
                            removalList.add(nextBreakend);

                            if(nextBreakend.chromosome().equals(nextOtherBe.chromosome()))
                            {
                                removalList.add(nextOtherBe);
                            }
                            else
                            {
                                removeRemoteBreakend(nextOtherBe, breakendRemovalMap);
                            }
                        }
                    }
                }
            }
        }

        // now remove filtered breakends
        for(Map.Entry<String,List<SvBreakend>> entry : breakendRemovalMap.entrySet())
        {
            final List<SvBreakend> removalList = entry.getValue();

            if(removalList.isEmpty())
                continue;

            final List<SvBreakend> breakendList = mChrBreakendMap.get(entry.getKey());
            removalList.stream().forEach(x -> breakendList.remove(x));

            // and reset indices after excluding breakends
            for (int i = 0; i < breakendList.size(); ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                breakend.setChrPosIndex(i);
            }
        }
    }

    private void removeRemoteBreakend(final SvBreakend breakend, Map<String,List<SvBreakend>> breakendRemovalMap)
    {
        List<SvBreakend> otherList = breakendRemovalMap.get(breakend.chromosome());
        if(otherList == null)
        {
            otherList = Lists.newArrayList();
            breakendRemovalMap.put(breakend.chromosome(), otherList);

        }

        otherList.add(breakend);
    }

    public void annotateNearestSvData()
    {
        // mark each SV's nearest other SV and its relationship - neighbouring or overlapping
        for(Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            List<SvBreakend> breakendList = entry.getValue();

            int breakendCount = breakendList.size();

            for(int i = 0; i < breakendList.size(); ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                SvVarData var = breakend.getSV();

                final SvBreakend prevBreakend = (i > 0) ? breakendList.get(i - 1) : null;
                final SvBreakend nextBreakend = (i < breakendCount-1) ? breakendList.get(i + 1) : null;

                long closestDistance = -1;
                if(prevBreakend != null && prevBreakend.getSV() != var)
                {
                    long distance = breakend.position() - prevBreakend.position();
                    closestDistance = distance;
                }

                if(nextBreakend != null && nextBreakend.getSV() != var)
                {
                    long distance = nextBreakend.position() - breakend.position();
                    if(closestDistance < 0 || distance < closestDistance)
                        closestDistance = distance;
                }

                if(closestDistance >= 0 && (var.getNearestSvDistance() == -1 || closestDistance < var.getNearestSvDistance()))
                    var.setNearestSvDistance(closestDistance);

                String relationType = "";
                if((prevBreakend != null && prevBreakend.getSV() == var) || (nextBreakend != null && nextBreakend.getSV() == var))
                    relationType = RELATION_TYPE_NEIGHBOUR;
                else
                    relationType = RELATION_TYPE_OVERLAP;

                var.setNearestSvRelation(relationType);
            }
        }
    }

    public boolean validateClustering(final List<SvCluster> clusters)
    {
        // validation that every SV was put into a cluster
        for (final Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            final List<SvBreakend> breakendList = entry.getValue();

            for (int i = 0; i < breakendList.size(); ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                SvVarData var = breakend.getSV();
                if(var.getCluster() == null)
                {
                    LOGGER.error("var({}) not clustered", var.posId());
                    return false;
                }
            }
        }

        // check that no 2 clusters contain the same SV
        for(int i = 0; i < clusters.size(); ++i)
        {
            SvCluster cluster1 = clusters.get(i);
            // isSpecificCluster(cluster1);

            // check all SVs in this cluster reference it
            for(SvVarData var : cluster1.getSVs())
            {
                if(var.getCluster() != cluster1)
                {
                    LOGGER.error("var({}) in cluster({}) has incorrect ref", var.posId(), cluster1.id());
                    return false;
                }
            }

            for(int j = i+1; j < clusters.size(); ++j)
            {
                SvCluster cluster2 = clusters.get(j);

                for(SvVarData var : cluster1.getSVs())
                {
                    if(cluster2.getSVs().contains(var))
                    {
                        LOGGER.error("var({}) in 2 clusters({} and {})", var.posId(), cluster1.id(), cluster2.id());
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public static boolean checkClusterDuplicates(List<SvCluster> clusters)
    {
        for(int i = 0; i < clusters.size(); ++i)
        {
            final SvCluster cluster1 = clusters.get(i);
            for(int j = i + 1; j < clusters.size(); ++j)
            {
                final SvCluster cluster2 = clusters.get(j);

                if(cluster1 == cluster2 || cluster1.id() == cluster2.id())
                {
                    LOGGER.error("cluster({}) exists twice in list", cluster1.id());
                    return false;
                }
            }
        }

        return true;
    }

}
