package com.hartwig.hmftools.linx.chaining;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.sqrt;

import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DEL;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DUP;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.copyNumbersEqual;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.formatPloidy;
import static com.hartwig.hmftools.linx.chaining.ChainLinkAllocator.SPEC_LINK_INDEX;
import static com.hartwig.hmftools.linx.chaining.ChainPloidyLimits.CLUSTER_ALLELE_PLOIDY_MIN;
import static com.hartwig.hmftools.linx.chaining.ChainPloidyLimits.CLUSTER_AP;
import static com.hartwig.hmftools.linx.chaining.ChainPloidyLimits.calcPloidyUncertainty;
import static com.hartwig.hmftools.linx.chaining.ChainPloidyLimits.ploidyOverlap;
import static com.hartwig.hmftools.linx.chaining.ChainingRule.ASSEMBLY;
import static com.hartwig.hmftools.linx.chaining.LinkFinder.areLinkedSection;
import static com.hartwig.hmftools.linx.chaining.LinkFinder.getMinTemplatedInsertionLength;
import static com.hartwig.hmftools.linx.chaining.ProposedLinks.CONN_TYPE_FOLDBACK;
import static com.hartwig.hmftools.linx.chaining.SvChain.checkIsValid;
import static com.hartwig.hmftools.linx.chaining.SvChain.reconcileChains;
import static com.hartwig.hmftools.linx.types.SvLinkedPair.LINK_TYPE_TI;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_END;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_PAIR;
import static com.hartwig.hmftools.linx.types.SvVarData.SE_START;
import static com.hartwig.hmftools.linx.types.SvVarData.isStart;

import static org.apache.logging.log4j.Level.TRACE;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hartwig.hmftools.linx.cn.PloidyCalcData;
import com.hartwig.hmftools.linx.types.SvBreakend;
import com.hartwig.hmftools.linx.types.SvCluster;
import com.hartwig.hmftools.linx.types.SvLinkedPair;
import com.hartwig.hmftools.linx.types.SvVarData;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

/* ChainFinder - forms one or more chains from the SVs in a cluster

    Set-up:
    - form all assembled links and connect into chains
    - identify foldbacks and complex DUP-type SVs, since these will be prioritised during chaining
    - create a cache of all possible linked pairs

    Routine:
    - apply priority rules to find the next possible link(s)
    - add the link to an existing chain or a new chain if required
    - remove the breakends & link from further consideration
    - repeat until no further links can be made

    Priority rules in order:
    - Single-Option - if a breakend has only one possible link, select this one
    - Foldbacks - look for a breakend which can link to both ends of a foldback
    - Foldback to foldback
    - Ploidy-Match - starting with the highest ploidy SV, only link SVs of the same ploidy
    - Shortest - after all other rules, if there is more than 1 possible link then choose the shortest
*/

public class ChainFinder
{
    private int mClusterId;
    public String mSampleId;
    private boolean mHasReplication;

    // input state
    private final List<SvVarData> mSvList;
    private final List<SvVarData> mFoldbacks;
    private final List<SvVarData> mDoubleMinuteSVs;
    private final List<SvLinkedPair> mAssembledLinks;
    private Map<String,List<SvBreakend>> mChrBreakendMap;

    // links a breakend to its position in a chromosome's breakend list - only applicable if a subset of SVs are being chained
    private boolean mIsClusterSubset;
    private final Map<SvBreakend,Integer> mSubsetBreakendClusterIndexMap;

    // chaining state
    private final Map<SvVarData,List<SvLinkedPair>> mComplexDupCandidates; // identified SVs which duplication another SV
    private final List<SvLinkedPair> mAdjacentMatchingPairs;
    private final List<SvLinkedPair> mAdjacentPairs;

    private final List<SvChain> mChains;
    private final List<SvChain> mUniqueChains;

    private ChainRuleSelector mRuleSelector;
    private ChainLinkAllocator mLinkAllocator;

    // a cache of cluster ploidy boundaries which links cannot cross
    private final ChainPloidyLimits mClusterPloidyLimits;

    // determined up-front - the set of all possible links from a specific breakend to other breakends
    private final Map<SvBreakend, List<SvLinkedPair>> mSvBreakendPossibleLinks;

    private List<SvVarData> mReplicatedSVs;
    private List<SvBreakend> mReplicatedBreakends;

    // temporary support for old chain finder
    private ChainFinderOld mOldFinder;
    private boolean mUseOld;
    private boolean mRunOldComparison;

    public static final int CHAIN_METHOD_OLD = 0;
    public static final int CHAIN_METHOD_NEW = 1;
    public static final int CHAIN_METHOD_COMPARE = 2;

    public static final double MIN_CHAINING_PLOIDY_LEVEL = 0.05;

    private boolean mIsValid;
    private boolean mLogVerbose;
    private Level mLogLevel;
    private boolean mRunValidation;
    private boolean mUseAllelePloidies;

    private static final String LR_METHOD_DM_CLOSE = "DM_CLOSE";

    // self-analysis only
    private final ChainDiagnostics mDiagnostics;

    private static final Logger LOGGER = LogManager.getLogger(ChainFinder.class);

    public ChainFinder()
    {
        mSvList = Lists.newArrayList();
        mFoldbacks = Lists.newArrayList();
        mDoubleMinuteSVs = Lists.newArrayList();
        mIsClusterSubset = false;
        mAssembledLinks = Lists.newArrayList();
        mChrBreakendMap = null;

        mClusterPloidyLimits = new ChainPloidyLimits();

        mAdjacentMatchingPairs = Lists.newArrayList();
        mAdjacentPairs = Lists.newArrayList();
        mSubsetBreakendClusterIndexMap = Maps.newHashMap();
        mComplexDupCandidates = Maps.newHashMap();
        mChains = Lists.newArrayList();
        mUniqueChains = Lists.newArrayList();
        mSvBreakendPossibleLinks = Maps.newHashMap();
        mReplicatedSVs = Lists.newArrayList();
        mReplicatedBreakends = Lists.newArrayList();

        mLinkAllocator = new ChainLinkAllocator(mSvBreakendPossibleLinks, mChains, mComplexDupCandidates);

        mRuleSelector = new ChainRuleSelector(mLinkAllocator,
                mSvBreakendPossibleLinks, mFoldbacks, mComplexDupCandidates,
                mAdjacentMatchingPairs, mAdjacentPairs, mChains);

        mHasReplication = false;
        mLogVerbose = false;
        mLogLevel = LOGGER.getLevel();
        mRunValidation = false;
        mIsValid = true;
        mSampleId= "";
        mUseAllelePloidies = false;

        mDiagnostics = new ChainDiagnostics(
                mLinkAllocator.getSvConnectionsMap(), mLinkAllocator.getSvCompletedConnections(), mChains, mUniqueChains,
                mSvBreakendPossibleLinks, mDoubleMinuteSVs, mLinkAllocator.getUniquePairs());

        mOldFinder = new ChainFinderOld();
        mUseOld = false;
        mRunOldComparison = false;
    }

    public void setUseOldMethod(boolean toggle, boolean runComparison)
    {
        LOGGER.info("using {} chain-finder", toggle ? "old" : "new");
        mUseOld = toggle;

        if(!mUseOld && runComparison)
        {
            LOGGER.info("running chaining comparison");
            mRunOldComparison = true;
        }
    }

    public void clear()
    {
        mClusterId = -1;
        mSvList.clear();
        mFoldbacks.clear();
        mDoubleMinuteSVs.clear();
        mIsClusterSubset = false;
        mAssembledLinks.clear();
        mChrBreakendMap = null;

        mAdjacentMatchingPairs.clear();
        mAdjacentPairs.clear();
        mSubsetBreakendClusterIndexMap.clear();
        mComplexDupCandidates.clear();
        mChains.clear();
        mUniqueChains.clear();
        mSvBreakendPossibleLinks.clear();
        mReplicatedSVs.clear();
        mReplicatedBreakends.clear();

        mIsValid = true;

        mDiagnostics.clear();
    }

    public void setSampleId(final String sampleId)
    {
        mSampleId = sampleId;
        mDiagnostics.setSampleId(sampleId);
    }

    public void initialise(SvCluster cluster)
    {
        // attempt to chain all the SVs in a cluster

        // critical that all state is cleared before the next run
        clear();

        // isSpecificCluster(cluster);

        mClusterId = cluster.id();
        mSvList.addAll(cluster.getSVs());
        mFoldbacks.addAll(cluster.getFoldbacks());
        mDoubleMinuteSVs.addAll(cluster.getDoubleMinuteSVs());
        mAssembledLinks.addAll(cluster.getAssemblyLinkedPairs());
        mChrBreakendMap = cluster.getChrBreakendMap();
        mHasReplication = cluster.requiresReplication();
        mIsClusterSubset = false;
        mLinkAllocator.initialise(mClusterId);
        mRuleSelector.initialise(mClusterId, mHasReplication);

        if(mUseOld || mRunOldComparison)
            mOldFinder.initialise(cluster);
    }

    public void initialise(SvCluster cluster, final List<SvVarData> svList)
    {
        // chain a specific subset of a cluster's SVs - current used to test double-minute completeness
        clear();

        mIsClusterSubset = true;
        mClusterId = cluster.id();

        mChrBreakendMap = Maps.newHashMap();

        for (final Map.Entry<String, List<SvBreakend>> entry : cluster.getChrBreakendMap().entrySet())
        {
            final List<SvBreakend> breakendList = Lists.newArrayList();

            for (final SvBreakend breakend : entry.getValue())
            {
                if (svList.contains(breakend.getSV()))
                {
                    mSubsetBreakendClusterIndexMap.put(breakend, breakendList.size());
                    breakendList.add(breakend);
                }
            }

            if (!breakendList.isEmpty())
            {
                mChrBreakendMap.put(entry.getKey(), breakendList);
            }
        }

        for(SvVarData var : svList)
        {
            if(!mHasReplication && var.isReplicatedSv())
            {
                mHasReplication = true;
                continue;
            }

            mSvList.add(var);

            if(var.isFoldback() && mFoldbacks.contains(var))
                mFoldbacks.add(var);

            for(int se = SE_START; se <= SE_END; ++se)
            {
                // only add an assembled link if it has a partner in the provided SV set, and can be replicated equally
                for (SvLinkedPair link : var.getAssembledLinkedPairs(isStart(se)))
                {
                    final SvVarData otherVar = link.getOtherSV(var);

                    if(!svList.contains(otherVar))
                        continue;

                    int maxRepCount = mHasReplication ? min(max(var.getReplicatedCount(),1), max(otherVar.getReplicatedCount(),1)) : 1;

                    long currentLinkCount = mAssembledLinks.stream().filter(x -> x.matches(link)).count();

                    if(currentLinkCount < maxRepCount)
                    {
                        mAssembledLinks.add(link);
                    }
                }
            }
        }

        mLinkAllocator.initialise(mClusterId);
        mRuleSelector.initialise(mClusterId, mHasReplication);

        mDoubleMinuteSVs.addAll(svList);
    }

    public void setRunValidation(boolean toggle) { mRunValidation = toggle; }
    public void setUseAllelePloidies(boolean toggle) { mUseAllelePloidies = toggle; }

    public final List<SvChain> getUniqueChains()
    {
        return mUseOld ? mOldFinder.getUniqueChains() : mUniqueChains;
    }
    public double getValidAllelePloidySegmentPerc() { return mClusterPloidyLimits.getValidAllelePloidySegmentPerc(); }
    public final ChainDiagnostics getDiagnostics() { return mDiagnostics; }

    public void formChains(boolean assembledLinksOnly)
    {
        if(mUseOld || mRunOldComparison)
        {
            mOldFinder.formChains(assembledLinksOnly);

            if(mUseOld)
                return;
        }

        if(mSvList.size() < 2)
            return;

        if (mSvList.size() >= 4)
        {
            LOGGER.debug("cluster({}) starting chaining with assemblyLinks({}) svCount({})",
                    mClusterId, mAssembledLinks.size(), mSvList.size());
        }

        enableLogVerbose();

        mClusterPloidyLimits.initialise(mClusterId, mChrBreakendMap);

        buildChains(assembledLinksOnly);

        checkChains();
        removeIdenticalChains();

        mDiagnostics.chainingComplete();

        if(mRunOldComparison && isValid())
            mOldFinder.compareChains(mSampleId, mUniqueChains, mDiagnostics.unlinkedSvCount());

        disableLogVerbose();

        if(!isValid())
        {
            LOGGER.warn("cluster({}) chain finding failed", mClusterId);
            return;
        }
    }

    private boolean isValid()
    {
        return mIsValid && mLinkAllocator.isValid();
    }

    private void removeIdenticalChains()
    {
        if(!mHasReplication)
        {
            mUniqueChains.addAll(mChains);
            return;
        }

        for(final SvChain newChain : mChains)
        {
            boolean matched = false;

            for(final SvChain chain : mUniqueChains)
            {
                if (chain.identicalChain(newChain, false))
                {
                    LOGGER.debug("cluster({}) skipping duplicate chain({}) ploidy({}) vs origChain({}) ploidy({})",
                            mClusterId, newChain.id(), formatPloidy(newChain.ploidy()), chain.id(), formatPloidy(chain.ploidy()));

                    // combine the ploidies
                    chain.setPloidyData(chain.ploidy() + newChain.ploidy(), chain.ploidyUncertainty());

                    // record repeated links
                    for(SvLinkedPair pair : chain.getLinkedPairs())
                    {
                        if(newChain.getLinkedPairs().stream().anyMatch(x -> x.matches(pair)))
                        {
                            pair.setRepeatCount(pair.repeatCount()+1);
                        }
                    }

                    matched = true;
                    break;
                }
            }

            if(!matched)
            {
                mUniqueChains.add(newChain);
            }
        }
    }

    public void addChains(SvCluster cluster)
    {
        if(mUseOld)
        {
            mOldFinder.addChains(cluster);
            return;
        }

        // add these chains to the cluster, but skip any which are identical to existing ones,
        // which can happen for clusters with replicated SVs
        mUniqueChains.stream().forEach(chain -> checkAddNewChain(chain, cluster));

        for(int i = 0; i < cluster.getChains().size(); ++i)
        {
            final SvChain chain = cluster.getChains().get(i);

            if(LOGGER.isDebugEnabled())
            {
                LOGGER.debug("cluster({}) added chain({}) ploidy({}) with {} linked pairs:",
                        mClusterId, chain.id(), formatPloidy(chain.ploidy()), chain.getLinkCount());
                chain.logLinks();
            }

            chain.setId(i); // set after logging so can compare with logging during building
        }
    }

    private void checkAddNewChain(final SvChain newChain, SvCluster cluster)
    {
        if(!mHasReplication)
        {
            cluster.addChain(newChain, false, true);
            return;
        }

        // any identical chains (including precise subsets of longer chains) will have their replicated SVs entirely removed
        if(!mUniqueChains.contains(newChain))
            return;

        cluster.addChain(newChain, false, true);
    }

    private void buildChains(boolean assembledLinksOnly)
    {
        mLinkAllocator.populateSvPloidyMap(mSvList, mHasReplication);

        mDiagnostics.initialise(mClusterId, mHasReplication);

        // first make chains out of any assembly links
        addAssemblyLinksToChains();

        if(assembledLinksOnly)
            return;

        if(mUseAllelePloidies && mHasReplication)
            mClusterPloidyLimits.determineBreakendPloidies();

        determinePossibleLinks();

        mDiagnostics.setPriorityData(Lists.newArrayList(mComplexDupCandidates.keySet()), mFoldbacks);

        int iterationsWithoutNewLinks = 0; // protection against loops

        while (true)
        {
            mLinkAllocator.clearPairSkipped();
            int lastAddedIndex = mLinkAllocator.getLinkIndex();

            if(lastAddedIndex == SPEC_LINK_INDEX)
            {
                LOGGER.debug("specifc link index({})", lastAddedIndex);
            }

            List<ProposedLinks> proposedLinks = mRuleSelector.findProposedLinks();

            if(proposedLinks.isEmpty())
            {
                if(!mLinkAllocator.pairSkipped())
                    break;
            }
            else
            {
                mLinkAllocator.processProposedLinks(proposedLinks);

                if (mRunValidation)
                {
                    checkChains();
                    mDiagnostics.checkHasValidState(mLinkAllocator.getLinkIndex());
                }

                if(!isValid())
                    return;
            }

            if(lastAddedIndex == mLinkAllocator.getLinkIndex())
            {
                ++iterationsWithoutNewLinks;

                if (iterationsWithoutNewLinks > 5)
                {
                    LOGGER.error("cluster({}) 5 iterations without adding a new link", mClusterId);
                    mIsValid = false;
                    break;
                }
            }
            else
            {
                iterationsWithoutNewLinks = 0;
            }

            mDiagnostics.checkProgress(mLinkAllocator.getLinkIndex());
        }

        if(mChains.size() < 50)
            reconcileChains(mChains, true, mLinkAllocator.getNextChainId());

        checkDoubleMinuteChains();
    }

    private void addAssemblyLinksToChains()
    {
        if(mAssembledLinks.isEmpty())
            return;

        if(!mHasReplication)
        {
            for (SvLinkedPair pair : mAssembledLinks)
            {
                ProposedLinks proposedLink = new ProposedLinks(pair, ASSEMBLY);
                proposedLink.addBreakendPloidies(
                        pair.getBreakend(true), mLinkAllocator.getUnlinkedBreakendCount(pair.getBreakend(true)),
                        pair.getBreakend(false), mLinkAllocator.getUnlinkedBreakendCount(pair.getBreakend(false)));

                if(!proposedLink.isValid())
                {
                    LOGGER.debug("cluster({}) skipping assembled link({}) with low ploidy", mClusterId, proposedLink);
                    continue;
                }

                mLinkAllocator.addLinks(proposedLink);
            }

            return;
        }

        // replicate any assembly links where the ploidy supports it, taking note of multiple connections between the same
        // breakend and other breakends eg if a SV has ploidy 2 and 2 different assembly links, it can only link once, whereas
        // if it has ploidy 2 and 1 link it should be made twice, and any higher combinations are unclear

        // first gather up all the breakends which have only one assembled link and record their ploidy
        List<SvBreakend> singleLinkBreakends = Lists.newArrayList();
        List<SvLinkedPair> bothMultiPairs = Lists.newArrayList();
        Map<SvBreakend,Double> breakendPloidies = Maps.newHashMap();

        List<SvLinkedPair> assemblyLinks = Lists.newArrayList(mAssembledLinks);

        // identify assembly links where both breakends have only 1 option, and links these immediately
        // make note of those where both breakends have multiple options
        int index = 0;
        while(index < assemblyLinks.size())
        {
            SvLinkedPair pair = assemblyLinks.get(index);
            final SvBreakend firstBreakend = pair.firstBreakend();
            final SvBreakend secondBreakend = pair.secondBreakend();

            boolean firstHasSingleConn = firstBreakend.getSV().getMaxAssembledBreakend() <= 1;
            boolean secondHasSingleConn = secondBreakend.getSV().getMaxAssembledBreakend() <= 1;

            double firstPloidy = mLinkAllocator.getUnlinkedBreakendCount(firstBreakend);
            double secondPloidy = mLinkAllocator.getUnlinkedBreakendCount(secondBreakend);

            if(firstPloidy == 0 || secondPloidy == 0)
            {
                LOGGER.debug("cluster({}) skipping assembled pair({}) with low ploidy({} & {})",
                        mClusterId, pair, formatPloidy(firstPloidy), formatPloidy(secondPloidy));

                assemblyLinks.remove(index);
                continue;
            }

            if(firstHasSingleConn && secondHasSingleConn)
            {
                ProposedLinks proposedLink = new ProposedLinks(pair, ASSEMBLY);
                proposedLink.addBreakendPloidies(firstBreakend, firstPloidy, secondBreakend, secondPloidy);
                mLinkAllocator.addLinks(proposedLink);

                assemblyLinks.remove(index);
                continue;
            }

            ++index;

            if(firstHasSingleConn)
                singleLinkBreakends.add(firstBreakend);
            else if(secondHasSingleConn)
                singleLinkBreakends.add(secondBreakend);

            if(!firstHasSingleConn && !secondHasSingleConn)
                bothMultiPairs.add(pair);

            breakendPloidies.put(firstBreakend, firstPloidy);
            breakendPloidies.put(secondBreakend, secondPloidy);
        }

        // now process those pairs where one breakend has only one assembled link
        index = 0;
        while(index < assemblyLinks.size())
        {
            SvLinkedPair pair = assemblyLinks.get(index);

            if(!bothMultiPairs.contains(pair))
            {
                final SvBreakend firstBreakend = pair.firstBreakend();
                final SvBreakend secondBreakend = pair.secondBreakend();

                boolean firstHasSingleConn = singleLinkBreakends.contains(firstBreakend);
                boolean secondHasSingleConn = singleLinkBreakends.contains(secondBreakend);

                double firstPloidy = firstHasSingleConn ? mLinkAllocator.getUnlinkedBreakendCount(firstBreakend)
                        : mLinkAllocator.getMaxUnlinkedBreakendCount(firstBreakend);

                double secondPloidy = secondHasSingleConn ? mLinkAllocator.getUnlinkedBreakendCount(secondBreakend)
                        : mLinkAllocator.getMaxUnlinkedBreakendCount(secondBreakend);

                if(firstPloidy == 0 || secondPloidy == 0)
                {
                    LOGGER.debug("cluster({}) pair({}) assembly links already exhausted: first({}) second({})",
                            mClusterId, pair.toString(), formatPloidy(firstPloidy), formatPloidy(secondPloidy));
                    assemblyLinks.remove(index);
                    continue;
                }

                // for the breakend which has other links to make, want to avoid indicating it has been matched
                ProposedLinks proposedLink = new ProposedLinks(pair, ASSEMBLY);
                proposedLink.addBreakendPloidies(firstBreakend, firstPloidy, secondBreakend, secondPloidy);

                if(!firstHasSingleConn && proposedLink.breakendPloidyMatched(firstBreakend))
                {
                    proposedLink.overrideBreakendPloidyMatched(firstBreakend);
                }
                else if(!secondHasSingleConn && proposedLink.breakendPloidyMatched(secondBreakend))
                {
                    proposedLink.overrideBreakendPloidyMatched(secondBreakend);
                }

                LOGGER.debug("assembly multi-sgl-conn pair({}) ploidy({}): first(ploidy={} links={}) second(ploidy={} links={})",
                        pair.toString(), formatPloidy(proposedLink.ploidy()),
                        formatPloidy(proposedLink.breakendPloidy(firstBreakend)), firstBreakend.getSV().getMaxAssembledBreakend(),
                        formatPloidy(proposedLink.breakendPloidy(secondBreakend)), secondBreakend.getSV().getMaxAssembledBreakend());

                mLinkAllocator.addLinks(proposedLink);
                assemblyLinks.remove(index);
                continue;
            }

            ++index;
        }

        // finally process the multi-connect options, most of which will now only have a single option left, and so the ploidy is known
        index = 0;
        boolean linkedPair = true;
        int iterations = 0;
        while(index < bothMultiPairs.size() && !bothMultiPairs.isEmpty())
        {
            ++iterations;
            final SvLinkedPair pair = bothMultiPairs.get(index);

            final SvBreakend firstBreakend = pair.firstBreakend();
            final SvBreakend secondBreakend = pair.secondBreakend();

            int firstRemainingLinks = firstBreakend.getSV().getAssembledLinkedPairs(firstBreakend.usesStart()).stream()
                        .filter(x -> bothMultiPairs.contains(x)).collect(Collectors.toList()).size();

            int secondRemainingLinks = secondBreakend.getSV().getAssembledLinkedPairs(secondBreakend.usesStart()).stream()
                    .filter(x -> bothMultiPairs.contains(x)).collect(Collectors.toList()).size();

            if(firstRemainingLinks == 0 || secondRemainingLinks == 0)
            {
                LOGGER.error("cluster({}) pair({}) unexpected remaining assembly link count: first({}) second({})",
                        mClusterId, pair.toString(), firstRemainingLinks, secondRemainingLinks);
                break;
            }

            double firstPloidy = mLinkAllocator.getMaxUnlinkedBreakendCount(firstBreakend);
            double secondPloidy = mLinkAllocator.getMaxUnlinkedBreakendCount(secondBreakend);

            if(firstPloidy == 0 || secondPloidy == 0)
            {
                LOGGER.debug("cluster({}) pair({}) assembly links already exhausted: first({}) second({})",
                        mClusterId, pair.toString(), formatPloidy(firstPloidy), formatPloidy(secondPloidy));
                bothMultiPairs.remove(index);
                continue;
            }

            ProposedLinks proposedLink = new ProposedLinks(pair, ASSEMBLY);

            if(firstRemainingLinks == 1 || secondRemainingLinks == 1)
            {
                proposedLink.addBreakendPloidies(firstBreakend, firstPloidy, secondBreakend, secondPloidy);
            }
            else if(!linkedPair)
            {
                proposedLink.addBreakendPloidies(
                        firstBreakend, firstPloidy/firstRemainingLinks,
                        secondBreakend, secondPloidy/secondRemainingLinks);
            }
            else
            {
                ++index;

                if(index >= bothMultiPairs.size())
                    index = 0;

                if(iterations > bothMultiPairs.size() * 3)
                {
                    LOGGER.warn("cluster({}) assembly multi-connection breakends missed", mClusterId);
                    break;
                }

                continue;
            }

            if(firstRemainingLinks > 1 && proposedLink.breakendPloidyMatched(firstBreakend))
            {
                proposedLink.overrideBreakendPloidyMatched(firstBreakend);
            }

            if(secondRemainingLinks > 1 && proposedLink.breakendPloidyMatched(secondBreakend))
            {
                proposedLink.overrideBreakendPloidyMatched(secondBreakend);
            }

            LOGGER.debug("assembly multi-conn pair({}) ploidy({}): first(ploidy={} links={}) second(ploidy={} links={})",
                    pair.toString(), formatPloidy(proposedLink.ploidy()),
                    formatPloidy(firstPloidy), firstBreakend.getSV().getMaxAssembledBreakend(),
                    formatPloidy(secondPloidy), secondBreakend.getSV().getMaxAssembledBreakend());

            mLinkAllocator.addLinks(proposedLink);
            linkedPair = true;
            bothMultiPairs.remove(index);
        }

        if(!mChains.isEmpty())
        {
            LOGGER.debug("created {} partial chains from {} assembly links", mChains.size(), mAssembledLinks.size());
        }
    }

    public boolean matchesExistingPair(final SvLinkedPair pair)
    {
        return mLinkAllocator.matchesExistingPair(pair);
    }

    private int getClusterChrBreakendIndex(final SvBreakend breakend)
    {
        if(!mIsClusterSubset)
            return breakend.getClusterChrPosIndex();

        Integer index = mSubsetBreakendClusterIndexMap.get(breakend);
        return index != null ? index : -1;
    }

    private void determinePossibleLinks()
    {
        // form a map of each breakend to its set of all other breakends which can form a valid TI
        // need to exclude breakends which are already assigned to an assembled TI unless replication permits additional instances of it
        // add possible links to a list ordered from shortest to longest length
        // do not chain past a zero cluster allele ploidy
        // identify potential complex DUP candidates along the way

        for (final Map.Entry<String, List<SvBreakend>> entry : mChrBreakendMap.entrySet())
        {
            final String chromosome = entry.getKey();
            final List<SvBreakend> breakendList = entry.getValue();
            final double[][] allelePloidies = mClusterPloidyLimits.getChrAllelePloidies().get(chromosome);

            for (int i = 0; i < breakendList.size() -1; ++i)
            {
                final SvBreakend lowerBreakend = breakendList.get(i);

                if(lowerBreakend.orientation() != -1)
                    continue;

                if(alreadyLinkedBreakend(lowerBreakend))
                    continue;

                List<SvLinkedPair> lowerPairs = null;

                final SvVarData lowerSV = lowerBreakend.getSV();

                boolean lowerValidAP = mUseAllelePloidies && mClusterPloidyLimits.hasValidAllelePloidyData(
                        getClusterChrBreakendIndex(lowerBreakend), allelePloidies);

                double lowerPloidy = mLinkAllocator.getUnlinkedBreakendCount(lowerBreakend);

                int skippedNonAssembledIndex = -1; // the first index of a non-assembled breakend after the current one

                for (int j = i+1; j < breakendList.size(); ++j)
                {
                    final SvBreakend upperBreakend = breakendList.get(j);

                    if(skippedNonAssembledIndex == -1)
                    {
                        if(!upperBreakend.isAssembledLink())
                        {
                            // invalidate the possibility of these 2 breakends satisfying the complex DUP scenario
                            skippedNonAssembledIndex = j;
                        }
                    }

                    if(upperBreakend.orientation() != 1)
                        continue;

                    if(upperBreakend.getSV() == lowerBreakend.getSV())
                        continue;

                    if(alreadyLinkedBreakend(upperBreakend))
                        continue;

                    long distance = upperBreakend.position() - lowerBreakend.position();
                    int minTiLength = getMinTemplatedInsertionLength(lowerBreakend, upperBreakend);

                    if(distance < minTiLength)
                        continue;

                    // record the possible link
                    final SvVarData upperSV = upperBreakend.getSV();

                    SvLinkedPair newPair = new SvLinkedPair(lowerSV, upperSV, LINK_TYPE_TI,
                            lowerBreakend.usesStart(), upperBreakend.usesStart());

                    // make note of any pairs formed from adjacent facing breakends, factoring in DBs in between
                    boolean areAdjacent = false;

                    if(j == i + 1)
                    {
                        // the breakends cannot form a DB with each other
                        if(lowerBreakend.getDBLink() == null || lowerBreakend.getDBLink() != upperBreakend.getDBLink())
                            areAdjacent = true;
                    }
                    else if(j == i + 2)
                    {
                        SvBreakend middleBreakend = breakendList.get(i + 1);

                        if((lowerBreakend.getDBLink() != null && lowerBreakend.getDBLink() == middleBreakend.getDBLink())
                        || (upperBreakend.getDBLink() != null && upperBreakend.getDBLink() == middleBreakend.getDBLink()))
                            areAdjacent = true;
                    }

                    if(areAdjacent)
                    {
                        mAdjacentPairs.add(newPair);

                        if (copyNumbersEqual(lowerPloidy, mLinkAllocator.getUnlinkedBreakendCount(upperBreakend)))
                            mAdjacentMatchingPairs.add(newPair);
                    }

                    if(lowerPairs == null)
                    {
                        lowerPairs = Lists.newArrayList();
                        mSvBreakendPossibleLinks.put(lowerBreakend, lowerPairs);
                    }

                    lowerPairs.add(newPair);

                    List<SvLinkedPair> upperPairs = mSvBreakendPossibleLinks.get(upperBreakend);

                    if(upperPairs == null)
                    {
                        upperPairs = Lists.newArrayList();
                        mSvBreakendPossibleLinks.put(upperBreakend, upperPairs);
                    }

                    upperPairs.add(0, newPair); // add to front since always nearer than the one prior

                    if(skippedNonAssembledIndex == -1 || skippedNonAssembledIndex == j)
                    {
                        // make note of any breakends which run into a high-ploidy SV at their first opposing breakend
                        if (!lowerBreakend.getSV().isFoldback())
                        {
                            checkIsComplexDupSV(lowerBreakend, upperBreakend);
                        }

                        if (!upperBreakend.getSV().isFoldback())
                        {
                            checkIsComplexDupSV(upperBreakend, lowerBreakend);
                        }
                    }

                    if(lowerValidAP && mClusterPloidyLimits.hasValidAllelePloidyData(
                            getClusterChrBreakendIndex(upperBreakend), allelePloidies))
                    {
                        double clusterAP = allelePloidies[getClusterChrBreakendIndex(upperBreakend)][CLUSTER_AP];

                        if(clusterAP < CLUSTER_ALLELE_PLOIDY_MIN)
                        {
                            // this lower breakend cannot match with anything further upstream
                            LOGGER.trace("breakends lower({}: {}) limited at upper({}: {}) with clusterAP({})",
                                    i, lowerBreakend.toString(), j, upperBreakend.toString(), formatPloidy(clusterAP));

                            break;
                        }
                    }
                }
            }
        }
    }

    private void checkIsComplexDupSV(SvBreakend lowerPloidyBreakend, SvBreakend higherPloidyBreakend)
    {
        // check if the lower ploidy SV connects to both ends of another SV to replicate it
        SvVarData var = lowerPloidyBreakend.getSV();

        if(var.isNullBreakend() || var.type() == DEL)
            return;

        if(mComplexDupCandidates.keySet().contains(var))
            return;

        if(mLinkAllocator.getSvConnectionsMap().get(var) == null)
            return;

        if(var.ploidyMin() * 2 > higherPloidyBreakend.getSV().ploidyMax())
            return;

        // skip very different ploidy-ratio breakends
        if(var.ploidyMax() * 4 < higherPloidyBreakend.getSV().ploidyMin())
            return;

        boolean lessThanMax = var.ploidyMax() < higherPloidyBreakend.getSV().ploidyMin();

        // check whether the other breakend satisfies the same ploidy comparison criteria
        SvBreakend otherBreakend = var.getBreakend(!lowerPloidyBreakend.usesStart());

        final List<SvBreakend> breakendList = mChrBreakendMap.get(otherBreakend.chromosome());

        boolean traverseUp = otherBreakend.orientation() == -1;
        int index = getClusterChrBreakendIndex(otherBreakend);

        while(true)
        {
            index += traverseUp ? 1 : -1;

            if(index < 0 || index >= breakendList.size())
                break;

            final SvBreakend breakend = breakendList.get(index);

            if(breakend == lowerPloidyBreakend)
                break;

            if (breakend.isAssembledLink())
            {
                index += traverseUp ? 1 : -1;
                continue;
            }

            if (breakend.orientation() == otherBreakend.orientation())
                break;

            SvVarData otherSV = breakend.getSV();

            if(var.ploidyMin() * 2 <= otherSV.ploidyMax())
            {
                if(lessThanMax || var.ploidyMax() < otherSV.ploidyMin())
                {
                    List<SvLinkedPair> links = Lists.newArrayList(SvLinkedPair.from(lowerPloidyBreakend, higherPloidyBreakend),
                            SvLinkedPair.from(otherBreakend, breakend));

                    if(otherSV == higherPloidyBreakend.getSV())
                    {
                        logInfo(String.format("identified complex dup(%s %s) ploidy(%.1f -> %.1f) vs SV(%s) ploidy(%.1f -> %.1f)",
                                var.posId(), var.type(), var.ploidyMin(), var.ploidyMax(), higherPloidyBreakend.getSV().id(),
                                higherPloidyBreakend.getSV().ploidyMin(), higherPloidyBreakend.getSV().ploidyMax()));
                    }
                    else
                    {
                        logInfo(String.format("identified complex dup(%s %s) ploidy(%.1f -> %.1f) vs SV(%s) ploidy(%.1f -> %.1f) & SV(%s) ploidy(%.1f -> %.1f)",
                                var.posId(), var.type(), var.ploidyMin(), var.ploidyMax(),
                                otherSV.id(), otherSV.ploidyMin(), otherSV.ploidyMax(), higherPloidyBreakend.getSV().id(),
                                higherPloidyBreakend.getSV().ploidyMin(), higherPloidyBreakend.getSV().ploidyMax()));
                    }

                    mComplexDupCandidates.put(var, links);
                }
            }

            break;
        }
    }

    private boolean alreadyLinkedBreakend(final SvBreakend breakend)
    {
        // assembled links have already been added to chains prior to determining remaining possible links
        // so these need to be excluded unless their replication count allows them to be used again
        return breakend.isAssembledLink() && mLinkAllocator.getUnlinkedBreakendCount(breakend) == 0;
    }

    private void checkDoubleMinuteChains()
    {
        // if there is a single chain which contains all DM SVs, attempt to close the chain
        if(mDoubleMinuteSVs.isEmpty())
            return;

        if(mChains.size() != 1)
            return;

        SvChain chain = mChains.get(0);

        int chainedDmSVs = (int)mDoubleMinuteSVs.stream().filter(x -> chain.hasSV(x, true)).count();

        if(chainedDmSVs != mDoubleMinuteSVs.size())
            return;

        SvBreakend chainStart = chain.getOpenBreakend(true);
        SvBreakend chainEnd = chain.getOpenBreakend(false);

        if(chainStart != null && !chainStart.getSV().isNullBreakend() && chainEnd != null && !chainEnd.getSV().isNullBreakend())
        {
            if (areLinkedSection(chainStart.getSV(), chainEnd.getSV(), chainStart.usesStart(), chainEnd.usesStart()))
            {
                SvLinkedPair pair = SvLinkedPair.from(chainStart, chainEnd);

                if (chain.linkWouldCloseChain(pair))
                {
                    chain.closeChain(LR_METHOD_DM_CLOSE, mLinkAllocator.getLinkIndex());
                    LOGGER.debug("cluster({}) closed DM chain", mClusterId);
                }
            }
        }
    }

    private void checkChains()
    {
        for(final SvChain chain : mChains)
        {
            if(!checkIsValid(chain))
            {
                LOGGER.error("cluster({}) has invalid chain({})", mClusterId, chain.id());
                chain.logLinks();
                mIsValid = false;
            }
        }

        // check no 2 chains have the same link reference
        for(int i = 0; i < mChains.size() - 1; ++i)
        {
            final SvChain chain1 = mChains.get(i);

            for(int j = i + 1; j < mChains.size(); ++j)
            {
                final SvChain chain2 = mChains.get(j);

                for(final SvLinkedPair pair : chain2.getLinkedPairs())
                {
                    if(chain1.getLinkedPairs().stream().anyMatch(x -> x == pair))
                    {
                        LOGGER.error("cluster({}) chain({}) and chain({}) share pair({})",
                                mClusterId, chain1.id(), chain2.id(), pair.toString());
                        mIsValid = false;
                    }
                }
            }
        }
    }

    protected void logInfo(final String message)
    {
        mDiagnostics.addMessage(message);
        LOGGER.debug(message);
    }

    public void setLogVerbose(boolean toggle)
    {
        mLogVerbose = toggle;
        setRunValidation(toggle);

        if(mUseOld)
            mOldFinder.setLogVerbose(toggle);
    }

    private void enableLogVerbose()
    {
        if(!mLogVerbose)
            return;

        mLogLevel = LOGGER.getLevel();
        Configurator.setRootLevel(TRACE);
    }

    private void disableLogVerbose()
    {
        if(!mLogVerbose)
            return;

        // restore logging
        Configurator.setRootLevel(mLogLevel);
    }

}