package com.hartwig.hmftools.linx.utils;

import static com.hartwig.hmftools.common.purple.copynumber.CopyNumberMethod.BAF_WEIGHTED;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantFactory.PASS;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.BND;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DEL;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.DUP;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.INF;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.INS;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.INV;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantType.SGL;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.CHROMOSOME_ARM_P;
import static com.hartwig.hmftools.linx.analysis.SvUtilities.getChromosomalArm;

import com.hartwig.hmftools.common.drivercatalog.DriverCatalog;
import com.hartwig.hmftools.common.drivercatalog.DriverCategory;
import com.hartwig.hmftools.common.drivercatalog.DriverType;
import com.hartwig.hmftools.common.drivercatalog.ImmutableDriverCatalog;
import com.hartwig.hmftools.common.drivercatalog.LikelihoodMethod;
import com.hartwig.hmftools.common.purple.gene.GeneCopyNumber;
import com.hartwig.hmftools.common.purple.gene.ImmutableGeneCopyNumber;
import com.hartwig.hmftools.common.purple.segment.SegmentSupport;
import com.hartwig.hmftools.common.variant.structural.ImmutableStructuralVariantData;
import com.hartwig.hmftools.common.variant.structural.StructuralVariantData;
import com.hartwig.hmftools.common.variant.structural.StructuralVariantType;
import com.hartwig.hmftools.linx.types.SvVarData;

public class SvTestUtils
{
    public static SvVarData createSv(final int varId, final String chrStart, final String chrEnd,
            long posStart, long posEnd, int orientStart, int orientEnd, StructuralVariantType type, final String insertSeq)
    {
        return createTestSv(varId, chrStart, chrEnd, posStart, posEnd, orientStart, orientEnd, type,
                2, 2, 1, 1, 1, insertSeq);
    }

    // for convenience
    public static SvVarData createDel(final int varId, final String chromosome, long posStart, long posEnd)
    {
        return createTestSv(varId, chromosome, chromosome, posStart, posEnd, 1, -1, DEL,
                2, 2, 1, 1, 1, "");
    }

    public static SvVarData createIns(final int varId, final String chromosome, long posStart, long posEnd)
    {
        return createTestSv(varId, chromosome, chromosome, posStart, posEnd, 1, -1, INS,
                2, 2, 1, 1, 1, "");
    }

    public static SvVarData createDup(final int varId, final String chromosome, long posStart, long posEnd)
    {
        return createTestSv(varId, chromosome, chromosome, posStart, posEnd, -1, 1, DUP,
                3, 3, 1, 1, 1, "");
    }

    public static SvVarData createInv(final int varId, final String chromosome, long posStart, long posEnd, int orientation)
    {
        return createTestSv(varId, chromosome, chromosome, posStart, posEnd, orientation, orientation, INV,
                orientation == 1 ? 4 : 3, orientation == 1 ? 3 : 4, 1, 1, 1, "");
    }

    public static SvVarData createSgl(final int varId, final String chromosome, long position, int orientation)
    {
        SvVarData var = createTestSv(varId, chromosome, "0", position, -1, orientation, -1, SGL,
                3, 0, 1, 0, 1, "");

        return var;
    }

    public static SvVarData createInf(final int varId, final String chromosome, long position, int orientation)
    {
        SvVarData var = createTestSv(varId, chromosome, "0", position, -1, orientation, -1, INF,
                3, 0, 1, 0, 1, "");

        return var;
    }

    public static SvVarData createBnd(final int varId, final String chrStart, long posStart, int orientStart, final String chrEnd, long posEnd, int orientEnd)
    {
        SvVarData var = createTestSv(varId, chrStart, chrEnd, posStart, posEnd, orientStart, orientEnd, BND,
                3, 3, 1, 1, 1, "");

        return var;
    }

    public static SvVarData createTestSv(final int varId, final String chrStart, final String chrEnd,
            long posStart, long posEnd, int orientStart, int orientEnd, StructuralVariantType type, double ploidy)
    {
        // let the copy number test data routine take care of setting CN and CN change data
        return createTestSv(varId, chrStart, chrEnd, posStart, posEnd, orientStart, orientEnd, type,
                0, 0, ploidy, ploidy, ploidy, "");
    }

    public static SvVarData createTestSv(final int varId, final String chrStart, final String chrEnd,
            long posStart, long posEnd, int orientStart, int orientEnd, StructuralVariantType type,
            double cnStart, double cnEnd, double cnChgStart, double cnChgEnd, double ploidy, final String insertSeq)
    {
        return createTestSv(varId, chrStart, chrEnd, posStart, posEnd, orientStart, orientEnd, type, cnStart, cnEnd, cnChgStart, cnChgEnd, ploidy, insertSeq, PASS);
    }

    public static SvVarData createTestSv(final int varId, final String chrStart, final String chrEnd,
            long posStart, long posEnd, int orientStart, int orientEnd, StructuralVariantType type,
            double cnStart, double cnEnd, double cnChgStart, double cnChgEnd, double ploidy, final String insertSeq,
            final String filter)
    {
        StructuralVariantData svData =
                ImmutableStructuralVariantData.builder()
                        .id(varId)
                        .startChromosome(chrStart)
                        .endChromosome(chrEnd)
                        .startPosition(posStart)
                        .endPosition(posEnd)
                        .startOrientation((byte)orientStart)
                        .endOrientation((byte)orientEnd)
                        .startHomologySequence("")
                        .endHomologySequence("")
                        .startAF(1.0)
                        .endAF(1.0)
                        .ploidy(ploidy)
                        .adjustedStartAF(1.0)
                        .adjustedEndAF(1.0)
                        .adjustedStartCopyNumber(cnStart)
                        .adjustedEndCopyNumber(cnEnd)
                        .adjustedStartCopyNumberChange(cnChgStart)
                        .adjustedEndCopyNumberChange(cnChgEnd)
                        .insertSequence(insertSeq)
                        .type(type)
                        .filter(filter)
                        .imprecise(false)
                        .qualityScore(0.0)
                        .event("")
                        .startTumorVariantFragmentCount(10)
                        .startTumorReferenceFragmentCount(10)
                        .startNormalVariantFragmentCount(10)
                        .startNormalReferenceFragmentCount(10)
                        .endTumorVariantFragmentCount(10)
                        .endTumorReferenceFragmentCount(10)
                        .endNormalVariantFragmentCount(10)
                        .endNormalReferenceFragmentCount(10)
                        .startIntervalOffsetStart(0)
                        .startIntervalOffsetEnd(0)
                        .endIntervalOffsetStart(0)
                        .endIntervalOffsetEnd(0)
                        .inexactHomologyOffsetStart(0)
                        .inexactHomologyOffsetEnd(0)
                        .startLinkedBy("")
                        .endLinkedBy("")
                        .vcfId("")
                        .startRefContext("")
                        .endRefContext("")
                        .recovered(false)
                        .recoveryMethod("")
                        .recoveryFilter("")
                        .insertSequenceAlignments("")
                        .insertSequenceRepeatClass("")
                        .insertSequenceRepeatType("")
                        .insertSequenceRepeatOrientation((byte)0)
                        .insertSequenceRepeatCoverage(0.0)
                        .startAnchoringSupportDistance(0)
                        .endAnchoringSupportDistance(0)
                        .build();

        SvVarData var = new SvVarData(svData);

        initialiseSV(var);

        return var;
    }

    public static void initialiseSV(SvVarData var)
    {
        String startArm = getChromosomalArm(var.chromosome(true), var.position(true));

        String endArm;
        if(!var.isSglBreakend())
            endArm = getChromosomalArm(var.chromosome(false), var.position(false));
        else
            endArm = CHROMOSOME_ARM_P;

        var.setChromosomalArms(startArm, endArm);

        // by default
        var.setPloidyRecalcData(var.getSvData().ploidy(), var.getSvData().ploidy());
    }


    public static GeneCopyNumber createGeneCopyNumber(final String gene, final String chromosome,
            double minCopyNumber, long posStart, long posEnd)
    {
        return ImmutableGeneCopyNumber.builder()
                .chromosome(chromosome)
                .chromosomeBand("")
                .start(posStart)
                .end(posEnd)
                .gene(gene)
                .maxCopyNumber(minCopyNumber)
                .minCopyNumber(minCopyNumber)
                .somaticRegions(0)
                .germlineHet2HomRegions(0)
                .germlineHomRegions(0)
                .minRegions(0)
                .minRegionStart(0)
                .minRegionEnd(0)
                .minRegionStartSupport(SegmentSupport.BND)
                .minRegionEndSupport(SegmentSupport.BND)
                .minRegionMethod(BAF_WEIGHTED)
                .minMinorAllelePloidy(0)
                .transcriptID("")
                .transcriptVersion(1)
                .build();
    }

    public static DriverCatalog createDriver(final String gene, final String chromosome, DriverType type, DriverCategory category,
            boolean biallelic, double minCopyNumber)
    {
        LikelihoodMethod method;
        if(type == DriverType.AMP)
            method = LikelihoodMethod.AMP;
        else if(type == DriverType.DEL)
            method = LikelihoodMethod.DEL;
        else
            method = LikelihoodMethod.BIALLELIC;

        return ImmutableDriverCatalog.builder()
                .biallelic(biallelic)
                .category(category)
                .gene(gene)
                .chromosome(chromosome)
                .chromosomeBand("")
                .driver(type)
                .driverLikelihood(1.0)
                .dndsLikelihood(1.0)
                .likelihoodMethod(method)
                .minCopyNumber(minCopyNumber)
                .maxCopyNumber(minCopyNumber)
                .missense(0)
                .nonsense(0)
                .splice(0)
                .inframe(0)
                .frameshift(0)
                .build();
    }


}
