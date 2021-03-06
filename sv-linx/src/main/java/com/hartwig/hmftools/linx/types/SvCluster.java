package com.hartwig.hmftools.linx.types;

import static java.lang.Math.ceil;
import static java.lang.Math.floor;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.round;

import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.BND;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.INF;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.SGL;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.typeAsInt;
import static com.hartwig.hmftools.linx.analysis.SimpleClustering.hasLowPloidy;
import static com.hartwig.hmftools.linx.analysis.SvClassification.isSimpleSingleSV;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.addSvToChrBreakendMap;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.appendStr;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.calcConsistency;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.getSvTypesStr;
import static com.hartwig.hmftools.linx.types.ResolvedType.LINE;
import static com.hartwig.hmftools.linx.types.ResolvedType.NONE;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_END;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_START;
import static com.hartwig.hmftools.linx.types.SvVarData.isStart;
import static com.hartwig.hmftools.linx.types.SvConstants.DEFAULT_CHAINING_SV_LIMIT;
import static com.hartwig.hmftools.linx.types.SvConstants.DEFAULT_PROXIMITY_DISTANCE;
import static com.hartwig.hmftools.linx.types.SvConstants.SHORT_TI_LENGTH;

import com.google.common.collect.Lists;
import com.hartwig.hmftools.common.variant.structural.StructuralVariantType;
import com.hartwig.hmftools.linx.analysis.ClusterMetrics;
import com.hartwig.hmftools.linx.analysis.SvClassification;
import com.hartwig.hmftools.linx.chaining.ChainMetrics;
import com.hartwig.hmftools.linx.chaining.SvChain;
import com.hartwig.hmftools.linx.cn.LohEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SvCluster
{
    private int mId;

    private int mConsistencyCount;
    private boolean mIsConsistent; // follows from telomere to centromere to telomore
    private String mDesc;
    private final int[] mTypeCounts;

    private boolean mIsResolved; // if true, then protect from subsequent merging
    private ResolvedType mResolvedType;

    private final List<String> mAnnotationList;

    private final List<SvVarData> mSVs;
    private final List<SvChain> mChains; // pairs of SVs linked into chains
    private final List<SvLinkedPair> mLinkedPairs; // final set after chaining and linking
    private final List<SvLinkedPair> mAssemblyLinkedPairs; // TIs found during assembly
    private final List<SvArmGroup> mArmGroups; // organise SVs into a group per chromosomal arm
    private final List<SvArmCluster> mArmClusters; // clusters of proximate SVs on an arm, currently only used for annotations
    private final Map<String, List<SvBreakend>> mChrBreakendMap; // note: does not contain replicated SVs
    private final List<SvVarData> mUnchainedSVs; // includes replicated SVs
    private final List<LohEvent> mLohEvents;
    private String mClusteringReasons;

    private boolean mRequiresReplication;

    // cached lists of identified special cases
    private final List<SvVarData> mLongDelDups;
    private final List<SvVarData> mFoldbacks;
    private final List<SvVarData> mDoubleMinuteSVs;
    private final List<SvVarData> mInversions;
    private SvChain mDoubleMinuteChain;
    private boolean mHasLinkingLineElements;
    private boolean mIsSubclonal;
    private boolean mRequiresRecalc;

    // state for SVs which link different arms or chromosomes
    private boolean mRecalcRemoteSVStatus;
    private final List<SvVarData> mShortTIRemoteSVs;
    private final List<SvVarData> mUnlinkedRemoteSVs;

    private double mMinPloidy;
    private double mMaxPloidy;

    private int mOriginArms;
    private int mFragmentArms;
    private int mConsistentArms;
    private int mComplexArms;
    private ClusterMetrics mMetrics;

    public static String CLUSTER_ANNOT_DM = "DM";
    public static String CLUSTER_ANNOT_BFB = "BFB";
    public static String CLUSTER_ANNOT_REP_REPAIR = "REP_REPAIR";
    public static String CLUSTER_ANNOT_SHATTERING = "CHROMO_THRIP";
    public static String CLUSTER_ANNOT_CHROMOPLEXY = "CHROMO_PLEX";

    private static final Logger LOGGER = LogManager.getLogger(SvCluster.class);

    public SvCluster(final int clusterId)
    {
        mId = clusterId;
        mSVs = Lists.newArrayList();
        mArmGroups = Lists.newArrayList();
        mArmClusters = Lists.newArrayList();
        mTypeCounts = new int[StructuralVariantType.values().length];

        // annotation info
        mDesc = "";
        mConsistencyCount = 0;
        mIsConsistent = false;
        mIsResolved = false;
        mResolvedType = NONE;
        mRequiresRecalc = true;
        mAnnotationList = Lists.newArrayList();
        mChrBreakendMap = new HashMap();

        // chain data
        mLinkedPairs = Lists.newArrayList();
        mAssemblyLinkedPairs= Lists.newArrayList();
        mChains = Lists.newArrayList();
        mUnchainedSVs = Lists.newArrayList();
        mLohEvents = Lists.newArrayList();

        mLongDelDups = Lists.newArrayList();
        mFoldbacks = Lists.newArrayList();
        mDoubleMinuteSVs = Lists.newArrayList();
        mDoubleMinuteChain = null;
        mHasLinkingLineElements = false;
        mInversions = Lists.newArrayList();
        mShortTIRemoteSVs = Lists.newArrayList();
        mUnlinkedRemoteSVs = Lists.newArrayList();
        mRecalcRemoteSVStatus = false;
        mIsSubclonal = false;

        mRequiresReplication = false;

        mMinPloidy = 0;
        mMaxPloidy = 0;
        mClusteringReasons = "";

        mOriginArms = 0;
        mFragmentArms = 0;
        mConsistentArms = 0;
        mComplexArms= 0;
        mMetrics = new ClusterMetrics();
    }

    public int id() { return mId; }

    public int getSvCount() { return mSVs.size(); }

    public final String getDesc() { return mDesc; }

    public final List<SvVarData> getSVs() { return mSVs; }
    public final SvVarData getSV(int index) { return index < mSVs.size() ? mSVs.get(index) : null; }

    public void addVariant(final SvVarData var)
    {
        if(mSVs.contains(var))
        {
            LOGGER.error("cluster({}) attempting to add SV({}) again", mId, var.id());
            return;
        }

        mSVs.add(var);
        mRequiresRecalc = true;

        mAnnotationList.clear();
        mDoubleMinuteSVs.clear();
        mDoubleMinuteChain = null;

        var.setCluster(this);

        mUnchainedSVs.add(var);

        if(!mHasLinkingLineElements)
        {
            mIsResolved = false;
            mResolvedType = NONE;
        }

        ++mTypeCounts[typeAsInt(var.type())];

        if (var.type() == BND || var.isCrossArm())
            mRecalcRemoteSVStatus = true;

        addSvToChrBreakendMap(var, mChrBreakendMap);

        // keep track of all SVs in their respective chromosomal arms
        for (int be = SE_START; be <= SE_END; ++be)
        {
            if (be == SE_END && var.isSglBreakend())
                continue;

            if (be == SE_END && var.isLocal())
                continue;

            boolean useStart = isStart(be);

            boolean groupFound = false;
            for (SvArmGroup armGroup : mArmGroups)
            {
                if (armGroup.chromosome().equals(var.chromosome(useStart)) && armGroup.arm().equals(var.arm(useStart)))
                {
                    armGroup.addVariant(var);
                    groupFound = true;
                    break;
                }
            }

            if (!groupFound)
            {
                SvArmGroup armGroup = new SvArmGroup(this, var.chromosome(useStart), var.arm(useStart));
                armGroup.addVariant(var);
                mArmGroups.add(armGroup);
            }
        }
    }

    public List<SvArmGroup> getArmGroups() { return mArmGroups; }
    public Map<String, List<SvBreakend>> getChrBreakendMap() { return mChrBreakendMap; }

    public boolean requiresReplication() { return mRequiresReplication; }

    public void setRequiresReplication()
    {
        mRequiresReplication = true;
    }

    public void addLohEvent(final LohEvent lohEvent)
    {
        if(!mLohEvents.contains(lohEvent))
            mLohEvents.add(lohEvent);
    }

    public final List<LohEvent> getLohEvents() { return mLohEvents; }

    public List<SvChain> getChains() { return mChains; }

    public void addChain(SvChain chain, boolean resetId)
    {
        if(resetId)
            chain.setId(mChains.size());

        mChains.add(chain);

        for(SvVarData var : chain.getSvList())
        {
            mUnchainedSVs.remove(var);
        }
    }

    public boolean isFullyChained(boolean requireConsistency)
    {
        if(!mUnchainedSVs.isEmpty() || mChains.isEmpty())
            return false;

        if(requireConsistency)
        {
            for (final SvChain chain : mChains)
            {
                if (!chain.isConsistent(requireConsistency))
                    return false;
            }
        }

        return true;
    }

    public void dissolveLinksAndChains()
    {
        mUnchainedSVs.clear();
        mUnchainedSVs.addAll(mSVs);
        mChains.clear();
    }

    public List<SvVarData> getUnlinkedSVs() { return mUnchainedSVs; }

    public final List<SvLinkedPair> getLinkedPairs() { return mLinkedPairs; }
    public final List<SvLinkedPair> getAssemblyLinkedPairs() { return mAssemblyLinkedPairs; }
    public void setAssemblyLinkedPairs(final List<SvLinkedPair> pairs)
    {
        mAssemblyLinkedPairs.clear();
        mAssemblyLinkedPairs.addAll(pairs);
    }

    public void mergeOtherCluster(final SvCluster other)
    {
        mergeOtherCluster(other, true);
    }

    public void mergeOtherCluster(final SvCluster other, boolean logDetails)
    {
        if(other == this || other.id() == id())
        {
            LOGGER.error("attempting to merge same cluster({})", id());
            return;
        }

        // just add the other cluster's variants - no preservation of links or chains
        if(other.getSvCount() > getSvCount())
        {
            if(logDetails)
            {
                LOGGER.debug("cluster({} svs={}) merges in other cluster({} svs={}) and adopts new ID",
                        id(), getSvCount(), other.id(), other.getSvCount());
            }

            // maintain the id of the larger group
            mId = other.id();
        }
        else if(logDetails)
        {
            LOGGER.debug("cluster({} svs={}) merges in other cluster({} svs={})",
                    id(), getSvCount(), other.id(), other.getSvCount());
        }

        addVariantLists(other);

        other.getLohEvents().stream().forEach(this::addLohEvent);

        String[] clusterReasons = other.getClusteringReasons().split(";");
        for(int i = 0; i < clusterReasons.length; ++i)
        {
            addClusterReason(clusterReasons[i]);
        }

        if(other.hasLinkingLineElements())
        {
            // retain status as a LINE cluster
            markAsLine();
        }

        // merge in fully assembled chains only
        other.getChains().stream()
                .filter(x -> x.getAssemblyLinkCount() == x.getLinkCount())
                .forEach(x -> mChains.add(x));
    }

    private void addVariantLists(final SvCluster other)
    {
        for(final SvVarData var : other.getSVs())
        {
            addVariant(var);
        }

        mAssemblyLinkedPairs.addAll(other.getAssemblyLinkedPairs());

        if(other.requiresReplication())
        {
            mRequiresReplication = true;
        }

        mInversions.addAll(other.getInversions());
        mFoldbacks.addAll(other.getFoldbacks());
        mLongDelDups.addAll(other.getLongDelDups());
    }

    public void addClusterReason(final String reason)
    {
        if(!mClusteringReasons.contains(reason))
        {
            mClusteringReasons = appendStr(mClusteringReasons, reason, ';');
        }
    }

    public final String getClusteringReasons() { return mClusteringReasons; }

    public boolean isConsistent()
    {
        updateClusterDetails();
        return mIsConsistent;
    }

    public int getConsistencyCount()
    {
        updateClusterDetails();
        return mConsistencyCount;
    }

    public void setResolved(boolean toggle, final ResolvedType type)
    {
        mIsResolved = toggle;
        mResolvedType = type;

        if(mDesc.isEmpty())
            mDesc = getClusterTypesAsString();
    }

    public boolean isResolved() { return mIsResolved; }
    public final ResolvedType getResolvedType() { return mResolvedType; }

    public boolean isSyntheticType()
    {
        if(mSVs.size() == 1)
            return false;

        return SvClassification.isSyntheticType(this);
    }

    private void updateClusterDetails()
    {
        if(!mRequiresRecalc)
            return;

        mConsistencyCount = calcConsistency(mSVs);

        mIsConsistent = (mConsistencyCount == 0);

        mDesc = getClusterTypesAsString();

        setMinMaxCNChange();

        resetBreakendMapIndices();

        mRequiresRecalc = false;
    }

    public void logDetails()
    {
        updateClusterDetails();

        if(isSimpleSingleSV(this))
        {
            LOGGER.debug("cluster({}) simple svCount({}) desc({}) armCount({}) consistency({}) ",
                    id(), getSvCount(), getDesc(), getArmCount(), getConsistencyCount());
        }
        else
        {
            double chainedPerc = 1 - (getUnlinkedSVs().size()/mSVs.size());

            String otherInfo = "";

            if(!mFoldbacks.isEmpty())
            {
                otherInfo += String.format("foldbacks=%d", mFoldbacks.size());
            }

            if(!mLongDelDups.isEmpty())
            {
                otherInfo = appendStr(otherInfo, String.format("longDelDup=%d", mLongDelDups.size()), ' ');
            }

            if(!mInversions.isEmpty())
            {
                otherInfo = appendStr(otherInfo, String.format("inv=%d", mInversions.size()), ' ');
            }

            if(!mDoubleMinuteSVs.isEmpty())
            {
                otherInfo = appendStr(otherInfo, "DM", ' ');
            }

            if(!mShortTIRemoteSVs.isEmpty())
            {
                otherInfo = appendStr(otherInfo, String.format("sti-bnd=%d", mShortTIRemoteSVs.size()), ' ');
            }

            if(!mUnlinkedRemoteSVs.isEmpty())
            {
                otherInfo = appendStr(otherInfo, String.format("unlnk-bnd=%d", mUnlinkedRemoteSVs.size()), ' ');
            }

            LOGGER.debug(String.format("cluster(%d) complex SVs(%d) desc(%s res=%s) arms(%d) consis(%d) chains(%d perc=%.2f) replic(%s) %s",
                    id(), getSvCount(), getDesc(), mResolvedType, getArmCount(), getConsistencyCount(),
                    mChains.size(), chainedPerc, mRequiresReplication, otherInfo));
        }
    }

    public int getArmCount() { return mArmGroups.size(); }

    public final String getClusterTypesAsString()
    {
        if(mSVs.size() == 1)
        {
            return mSVs.get(0).typeStr();
        }

        return getSvTypesStr(mTypeCounts);
    }

    public int getSglBreakendCount()
    {
        return mTypeCounts[typeAsInt(SGL)] + mTypeCounts[typeAsInt(INF)];
    }

    public int getTypeCount(StructuralVariantType type)
    {
        return mTypeCounts[typeAsInt(type)];
    }

    public final List<SvVarData> getLongDelDups() { return mLongDelDups; }
    public final List<SvVarData> getFoldbacks() { return mFoldbacks; }
    public final List<SvVarData> getInversions() { return mInversions; }
    public final List<SvVarData> getDoubleMinuteSVs() { return mDoubleMinuteSVs; }
    public final SvChain getDoubleMinuteChain() { return mDoubleMinuteChain; }

    public void registerFoldback(final SvVarData var)
    {
        if(!mFoldbacks.contains(var))
            mFoldbacks.add(var);
    }

    public void deregisterFoldback(final SvVarData var)
    {
        if(mFoldbacks.contains(var))
            mFoldbacks.remove(var);
    }

    public void registerInversion(final SvVarData var)
    {
        if(!mInversions.contains(var))
            mInversions.add(var);
    }

    public void registerLongDelDup(final SvVarData var)
    {
        if(!mLongDelDups.contains(var))
            mLongDelDups.add(var);
    }

    public void setDoubleMinuteData(final List<SvVarData> svList, final SvChain chain)
    {
        mDoubleMinuteSVs.clear();
        mDoubleMinuteSVs.addAll(svList);
        mDoubleMinuteChain = chain;
    }

    public void markAsLine()
    {
        mHasLinkingLineElements = true;
        setResolved(true, LINE);
    }

    public boolean hasLinkingLineElements() { return mHasLinkingLineElements; }

    public void markSubclonal()
    {
        long lowCNChangeSupportCount = mSVs.stream().filter(x -> hasLowPloidy(x)).count();
        mIsSubclonal = lowCNChangeSupportCount / (double)mSVs.size() > 0.5;
    }

    public boolean isSubclonal() { return mIsSubclonal; }

    public final List<SvVarData> getUnlinkedRemoteSVs() { return mUnlinkedRemoteSVs; }

    public void setArmLinks()
    {
        if(!mRecalcRemoteSVStatus)
            return;

        // keep track of BND which are or aren't candidates for links between arms
        mShortTIRemoteSVs.clear();
        mUnlinkedRemoteSVs.clear();

        for (final SvChain chain : mChains)
        {
            // any pair of remote SVs which don't form a short TI are fair game
            for (final SvLinkedPair pair : chain.getLinkedPairs())
            {
                if (pair.first().isCrossArm() && pair.second().isCrossArm() && pair.length() <= SHORT_TI_LENGTH)
                {
                    if (!mShortTIRemoteSVs.contains(pair.first()))
                        mShortTIRemoteSVs.add(pair.first());

                    if (!mShortTIRemoteSVs.contains(pair.second()))
                        mShortTIRemoteSVs.add(pair.second());
                }
            }
        }

        mUnlinkedRemoteSVs.clear();
        mUnlinkedRemoteSVs.addAll(mSVs.stream()
                .filter(x -> x.isCrossArm())
                .filter(x -> !x.inLineElement())
                .filter(x -> !mShortTIRemoteSVs.contains(x))
                .collect(Collectors.toList()));

        mRecalcRemoteSVStatus = false;
    }

    private void setMinMaxCNChange()
    {
        if(mSVs.size() == 1)
        {
            mMinPloidy = mMaxPloidy = mSVs.get(0).ploidy();
            return;
        }

        // establish the high and low ploidy from all SVs
        mMinPloidy = -1;
        mMaxPloidy = 0;

        int svCalcPloidyCount = 0;
        Map<Integer,Integer> ploidyFrequency = new HashMap();

        double tightestMinPloidy = 0;
        double tightestMaxPloidy = -1;
        int countHalfToOnePloidy = 0;
        double minSvPloidy = -1;
        int maxAssembledMultiple = 1; // the highest multiple of a breakend linked to other assembled breakends

        for (final SvVarData var : mSVs)
        {
            int svPloidy = var.getImpliedPloidy();
            maxAssembledMultiple = max(maxAssembledMultiple, var.getMaxAssembledBreakend());

            if (mMinPloidy < 0 || svPloidy < mMinPloidy)
            {
                mMinPloidy = svPloidy;
                minSvPloidy = svPloidy;
            }

            mMaxPloidy = max(mMaxPloidy, svPloidy);

            if(var.hasCalculatedPloidy())
            {
                ++svCalcPloidyCount;

                int minPloidyInt = (int)ceil(var.ploidyMin());
                int maxPloidyInt = (int)floor(var.ploidyMax());
                maxPloidyInt = max(minPloidyInt, maxPloidyInt);

                if(tightestMaxPloidy == -1 || var.ploidyMax() < tightestMaxPloidy)
                    tightestMaxPloidy = var.ploidyMax();

                tightestMinPloidy = max(var.ploidyMin(), tightestMinPloidy);

                if(var.ploidyMin() < 1 && var.ploidyMax() > 0.5)
                    ++countHalfToOnePloidy;

                for(int i = minPloidyInt; i <= maxPloidyInt; ++i)
                {
                    Integer svCount = ploidyFrequency.get(i);
                    if(svCount == null)
                        ploidyFrequency.put(i, 1);
                    else
                        ploidyFrequency.put(i, svCount+1);
                }
            }
        }

        if(svCalcPloidyCount > 0)
        {
            mMinPloidy = -1;
            mMaxPloidy = 0;

            for (Map.Entry<Integer, Integer> entry : ploidyFrequency.entrySet())
            {
                int ploidy = entry.getKey();
                int svCount = entry.getValue();

                if (svCount == svCalcPloidyCount)
                {
                    // all SVs can settle on the same ploidy value, so take this
                    mMaxPloidy = ploidy;
                    mMinPloidy = ploidy;
                    break;
                }

                if (ploidy > 0 && (mMinPloidy < 0 || ploidy < mMinPloidy))
                     mMinPloidy = ploidy;

                mMinPloidy = max(mMinPloidy, minSvPloidy);
                mMaxPloidy = max(mMaxPloidy, ploidy);
            }

            if(mMinPloidy < mMaxPloidy && maxAssembledMultiple == 1)
            {
                if (tightestMaxPloidy > tightestMinPloidy && tightestMaxPloidy - tightestMinPloidy < 1)
                {
                    // if all SVs cover the same value but it's not an integer, still consider them uniform
                    mMinPloidy = 1;
                    mMaxPloidy = 1;
                }
                else if (countHalfToOnePloidy == svCalcPloidyCount)
                {
                    mMinPloidy = 1;
                    mMaxPloidy = 1;
                }
            }
        }

        // correct for the ploidy ratios implied from the assembled links
        if(maxAssembledMultiple > 1)
        {
            mMaxPloidy = max(mMaxPloidy, maxAssembledMultiple * mMinPloidy);
        }
    }

    public double getMaxPloidy() { return mMaxPloidy; }
    public double getMinPloidy() { return mMinPloidy; }

    public boolean hasVariedPloidy()
    {
        if(mRequiresRecalc)
            updateClusterDetails();

        if(mSVs.size() == 1)
            return false;

        return (mMaxPloidy > mMinPloidy && mMinPloidy >= 0);
    }

    private void resetBreakendMapIndices()
    {
        for (Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            List<SvBreakend> breakendList = entry.getValue();

            for (int i = 0; i < breakendList.size(); ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                breakend.setClusterChrPosIndex(i);
            }
        }
    }

    public void cacheLinkedPairs()
    {
        // moves assembly and unique inferred linked pairs which are used in chains to a set of 'final' linked pairs
        mLinkedPairs.clear();

        for (final SvChain chain : mChains)
        {
            for (final SvLinkedPair pair : chain.getLinkedPairs())
            {
                if(mLinkedPairs.stream().anyMatch(x -> x.matches(pair)))
                    continue;

                mLinkedPairs.add(pair);
                pair.first().addLinkedPair(pair, pair.firstLinkOnStart());
                pair.second().addLinkedPair(pair, pair.secondLinkOnStart());
            }
        }
    }

    public final SvChain findChain(final SvVarData var)
    {
        for(final SvChain chain : mChains)
        {
            if(chain.getSvIndex(var) >= 0)
                return chain;
        }

        return null;
    }

    public final List<SvChain> findChains(final SvVarData var)
    {
        return mChains.stream().filter(x -> x.hasSV(var)).collect(Collectors.toList());
    }

    public final SvChain findSameChainForSVs(SvVarData var1, SvVarData var2)
    {
        List<SvChain> chains1 = findChains(var1);
        List<SvChain> chains2 = findChains(var2);

        for(SvChain chain1 : chains1)
        {
            for(SvChain chain2 : chains2)
            {
                if(chain1 == chain2)
                    return chain1;
            }
        }

        return null;
    }

    public int getChainId(final SvVarData var)
    {
        final SvChain chain = findChain(var);

        if(chain != null)
            return chain.id();

        // otherwise set an id based on index in the unchained variants list
        for(int i = 0; i < mUnchainedSVs.size(); ++i)
        {
            final SvVarData unchainedSv = mUnchainedSVs.get(i);

            if(unchainedSv == var)
                return mChains.size() + i + 1;
        }

        return var.id();
    }

    public final List<SvArmCluster> getArmClusters() { return mArmClusters; }

    public void buildArmClusters()
    {
        for (Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            List<SvBreakend> breakendList = entry.getValue();

            SvArmCluster prevArmCluster = null;

            for (int i = 0; i < breakendList.size(); ++i)
            {
                final SvBreakend breakend = breakendList.get(i);
                SvVarData var = breakend.getSV();

                // ensure that a pair of foldback breakends are put into the same arm cluster
                if(var.isFoldback() && var.getFoldbackBreakend(breakend.usesStart()) != null)
                {
                    SvBreakend otherFoldbackBreakend = var.getFoldbackBreakend(breakend.usesStart());
                    SvArmCluster existingAC = findArmCluster(otherFoldbackBreakend);

                    if(existingAC != null)
                    {
                        existingAC.addBreakend(breakend);
                        continue;
                    }
                }

                // first test the previous arm cluster
                if(prevArmCluster != null)
                {
                    if(breakend.arm() == prevArmCluster.arm() && breakend.position() - prevArmCluster.posEnd() <= DEFAULT_PROXIMITY_DISTANCE)
                    {
                        prevArmCluster.addBreakend(breakend);
                        continue;
                    }

                    // prevArmCluster = null;
                }

                boolean groupFound = false;

                for(final SvArmCluster armCluster : mArmClusters)
                {
                    if(!breakend.chromosome().equals(armCluster.chromosome()) || breakend.arm() != armCluster.arm())
                        continue;

                    // test whether position is within range
                    if(breakend.position() >= armCluster.posStart() - DEFAULT_PROXIMITY_DISTANCE
                    && breakend.position() <= armCluster.posEnd() + DEFAULT_PROXIMITY_DISTANCE)
                    {
                        armCluster.addBreakend(breakend);
                        groupFound = true;
                        prevArmCluster = armCluster;
                        break;
                    }
                }

                if(!groupFound)
                {
                    SvArmCluster armCluster = new SvArmCluster(mArmClusters.size(), this, breakend.chromosome(), breakend.arm());
                    armCluster.addBreakend(breakend);
                    mArmClusters.add(armCluster);
                    prevArmCluster = armCluster;
                }
            }
        }

        mArmClusters.forEach(x -> x.setFeatures());
    }

    public SvArmCluster findArmCluster(final SvBreakend breakend)
    {
        for(final SvArmCluster armCluster : mArmClusters)
        {
            if(armCluster.getBreakends().contains(breakend))
                return armCluster;
        }

        return null;
    }

    public void setArmData(int origins, int fragments, int consistentArms, int complexArms)
    {
        mOriginArms = origins;
        mFragmentArms = fragments;
        mConsistentArms = consistentArms;
        mComplexArms = complexArms;
    }
    public int getOriginArms() { return mOriginArms; }
    public int getFragmentArms() { return mFragmentArms; }
    public int getConsistentArms() { return mConsistentArms; }
    public int getComplexArms() { return mComplexArms; }

    public ClusterMetrics getMetrics() { return mMetrics; }

    public ChainMetrics getLinkMetrics()
    {
        ChainMetrics chainMetrics = new ChainMetrics();
        mChains.stream().forEach(x -> chainMetrics.add(x.extractChainMetrics()));
        return chainMetrics;
    }

    public final List<String> getAnnotationList() { return mAnnotationList; }

    public final void addAnnotation(final String annotation)
    {
        if(mAnnotationList.contains(annotation))
            return;

        mAnnotationList.add(annotation);
    }

    public boolean hasAnnotation(final String annotation) { return mAnnotationList.contains(annotation); }

    public String getAnnotations() { return mAnnotationList.stream().collect (Collectors.joining (";")); }

    public void setPloidyReplication(int chainingSvLimit)
    {
        if(!hasVariedPloidy() && !requiresReplication())
            return;

        // use the relative copy number change to replicate some SVs within a cluster
        double clusterMinPloidy = getMinPloidy();
        double clusterMaxPloidy = getMaxPloidy();

        if(clusterMinPloidy <= 0)
        {
            LOGGER.debug("cluster({}) warning: invalid ploidy variation(min={} max={})",
                    mId, clusterMinPloidy, clusterMaxPloidy);
            return;
        }

        // check for samples with a broad range of ploidies, not just concentrated in a few SVs
        int totalReplicationCount = 0;
        double replicationFactor = 1;

        for(SvVarData var : mSVs)
        {
            int svPloidy = var.getImpliedPloidy();
            int svMultiple = (int)max(round(svPloidy / clusterMinPloidy),1);
            totalReplicationCount += svMultiple;
        }

        int replicationCap = chainingSvLimit > 0 ? min(chainingSvLimit, DEFAULT_CHAINING_SV_LIMIT) : DEFAULT_CHAINING_SV_LIMIT;
        if(totalReplicationCount > replicationCap)
        {
            LOGGER.debug("cluster({}) totalRepCount({}) vs svCount({}) with cluster ploidy(min={} min={}) will be scaled vs limit({})",
                    mId, totalReplicationCount, getSvCount(), clusterMinPloidy, clusterMaxPloidy, replicationCap);

            replicationFactor = replicationCap / (double)totalReplicationCount;
        }

        // replicate the SVs which have a higher copy number than their peers
        for(SvVarData var : mSVs)
        {
            int svPloidy = var.getImpliedPloidy();
            int maxAssemblyBreakends = var.getMaxAssembledBreakend();

            int svMultiple = (int)round(svPloidy / clusterMinPloidy);

            if(maxAssemblyBreakends > 1)
                svMultiple = max(svMultiple, maxAssemblyBreakends);

            svMultiple = max((int)round(svMultiple * replicationFactor), 1);

            if(svMultiple <= 1)
                continue;

            LOGGER.debug("cluster({}) SV({}) ploidy multiple({}, ploidy({} vs min={})",
                    mId, var.posId(), svMultiple, svPloidy, clusterMinPloidy);

            if(!requiresReplication())
                setRequiresReplication();
        }
    }



}
