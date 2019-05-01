package com.hartwig.hmftools.patientreporter.variants.germline;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.hartwig.hmftools.common.drivercatalog.DriverCategory;
import com.hartwig.hmftools.common.purple.gene.GeneCopyNumber;
import com.hartwig.hmftools.common.variant.EnrichedSomaticVariant;
import com.hartwig.hmftools.patientreporter.PatientReporterTestFactory;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

public class FilterGermlineVariantsTest {

    private static final String ONCOGENE = "ONCO";
    private static final String TSG = "TSG";

    @Test
    public void checkForGermlineGenesReportedONCO() {
        GermlineReportingModel germlineReportingModel = PatientReporterTestFactory.createTestGermlineGenesReporting();
        Map<String, DriverCategory> driverCategoryMap = PatientReporterTestFactory.createTestDriverCategoryMap();

        List<GeneCopyNumber> geneCopyNumbers = Lists.newArrayList();
        List<EnrichedSomaticVariant> somaticVariants = Lists.newArrayList();

        List<GermlineVariant> germlineVariants = createTestGermlineVariantsONCOGene();
        List<GermlineVariant> filteredGermlineVariantMatch = FilterGermlineVariants.filterGermlineVariantsForReporting(germlineVariants,
                germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbers,
                somaticVariants);
        assertEquals(1, filteredGermlineVariantMatch.size());
    }

    @Test
    public void checkForGermlineGenesReportedTSG() {
        GermlineReportingModel germlineReportingModel = PatientReporterTestFactory.createTestGermlineGenesReporting();
        Map<String, DriverCategory> driverCategoryMap = PatientReporterTestFactory.createTestDriverCategoryMap();

        List<GermlineVariant> germlineVariantsMatch = createTestGermlineVariantsTSGGene(true);
        List<GeneCopyNumber> geneCopyNumbersMatch = createCopyNumberListForTSG(1);
        List<EnrichedSomaticVariant> variantsMatch = createEnrichedListForGene(TSG);
        List<GermlineVariant> filteredGermlineVariantMatch =
                FilterGermlineVariants.filterGermlineVariantsForReporting(germlineVariantsMatch, germlineReportingModel,
                        driverCategoryMap,
                        geneCopyNumbersMatch,
                        variantsMatch);
        assertEquals(1, filteredGermlineVariantMatch.size()); // all three options matched

        List<GermlineVariant> germlineVariantsNonMatchBiallelic = createTestGermlineVariantsTSGGene(false);
        List<GeneCopyNumber> geneCopyNumbersNonMatchBiallelic = createCopyNumberListForTSG(1);
        List<EnrichedSomaticVariant> variantsNonMatchBiallelic = createEnrichedListForGene(TSG);
        List<GermlineVariant> filteredGermlineVariantNonMatchBiallelic = FilterGermlineVariants.filterGermlineVariantsForReporting(
                germlineVariantsNonMatchBiallelic, germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbersNonMatchBiallelic,
                variantsNonMatchBiallelic);
        assertEquals(1, filteredGermlineVariantNonMatchBiallelic.size()); // match copy number and variant

        List<GermlineVariant> germlineVariantsNonMatchVariant = createTestGermlineVariantsTSGGene(true);
        List<GeneCopyNumber> geneCopyNumbersNonMatchVariant = createCopyNumberListForTSG(1);
        List<EnrichedSomaticVariant> variantsNonMatchVariant = createEnrichedListForGene("AAAA");
        List<GermlineVariant> filteredGermlineVariantNonMatchVariant = FilterGermlineVariants.filterGermlineVariantsForReporting(
                germlineVariantsNonMatchVariant, germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbersNonMatchVariant,
                variantsNonMatchVariant);
        assertEquals(1, filteredGermlineVariantNonMatchVariant.size()); // match biallelic and copy number

        List<GermlineVariant> germlineVariantsNonMatchCopy = createTestGermlineVariantsTSGGene(true);
        List<GeneCopyNumber> geneCopyNumbersNonMatchCopy = createCopyNumberListForTSG(2);
        List<EnrichedSomaticVariant> variantsNonMatchCopy = createEnrichedListForGene(TSG);
        List<GermlineVariant> filteredGermlineVariantNonMatchCopy = FilterGermlineVariants.filterGermlineVariantsForReporting(
                germlineVariantsNonMatchCopy, germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbersNonMatchCopy,
                variantsNonMatchCopy);
        assertEquals(1, filteredGermlineVariantNonMatchCopy.size()); // match biallelic and variant

        List<GermlineVariant> germlineVariantsNonMatch = createTestGermlineVariantsTSGGene(false);
        List<GeneCopyNumber> geneCopyNumbersNonMatch = createCopyNumberListForTSG(2);
        List<EnrichedSomaticVariant> variantsNonMatch = createEnrichedListForGene("AAAA");
        List<GermlineVariant> filteredGermlineVariantNonMatch = FilterGermlineVariants.filterGermlineVariantsForReporting(
                germlineVariantsNonMatch, germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbersNonMatch,
                variantsNonMatch);
        assertEquals(0, filteredGermlineVariantNonMatch.size()); // all option failed

        List<GermlineVariant> germlineVariantsOptionBiallelic = createTestGermlineVariantsTSGGene(true);
        List<GeneCopyNumber> geneCopyNumbersOptionBiallelic = createCopyNumberListForTSG(2);
        List<EnrichedSomaticVariant> variantsOptionBiallelic = createEnrichedListForGene("AAAA");
        List<GermlineVariant> filteredGermlineVariantOptionBiallelic = FilterGermlineVariants.filterGermlineVariantsForReporting(
                germlineVariantsOptionBiallelic, germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbersOptionBiallelic,
                variantsOptionBiallelic);
        assertEquals(1, filteredGermlineVariantOptionBiallelic.size()); // only match biallelic

        List<GermlineVariant> germlineVariantsOptionVariant = createTestGermlineVariantsTSGGene(false);
        List<GeneCopyNumber> geneCopyNumbersOptionVariant = createCopyNumberListForTSG(2);
        List<EnrichedSomaticVariant> variantsOptionVariant = createEnrichedListForGene(TSG);
        List<GermlineVariant> filteredGermlineVariantOptionVariant = FilterGermlineVariants.filterGermlineVariantsForReporting(
                germlineVariantsOptionVariant, germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbersOptionVariant,
                variantsOptionVariant);
        assertEquals(1, filteredGermlineVariantOptionVariant.size()); // only match variant

        List<GermlineVariant> germlineVariantsOptionCopyNumber = createTestGermlineVariantsTSGGene(false);
        List<GeneCopyNumber> geneCopyNumbersCopyNumber = createCopyNumberListForTSG(1);
        List<EnrichedSomaticVariant> variantsOptionCopyNumber = createEnrichedListForGene("AAAA");
        List<GermlineVariant> filteredGermlineVariantOptionCopyNumber = FilterGermlineVariants.filterGermlineVariantsForReporting(
                germlineVariantsOptionCopyNumber, germlineReportingModel,
                driverCategoryMap,
                geneCopyNumbersCopyNumber,
                variantsOptionCopyNumber);
        assertEquals(1, filteredGermlineVariantOptionCopyNumber.size()); // only match copy number
    }

    @NotNull
    private static List<GermlineVariant> createTestGermlineVariantsONCOGene() {
        return Lists.newArrayList(PatientReporterTestFactory.createTestGermlineVariantBuilder().gene(ONCOGENE).build());
    }

    @NotNull
    private static List<GermlineVariant> createTestGermlineVariantsTSGGene(boolean biallelicFilter) {
        return Lists.newArrayList(PatientReporterTestFactory.createTestGermlineVariantBuilder()
                .gene(TSG)
                .biallelic(biallelicFilter)
                .build());
    }

    @NotNull
    private static List<GeneCopyNumber> createCopyNumberListForTSG(int minCopyNumber) {
        return Lists.newArrayList(PatientReporterTestFactory.createTestCopyNumberBuilder().gene(TSG).minCopyNumber(minCopyNumber).build());
    }

    @NotNull
    private static List<EnrichedSomaticVariant> createEnrichedListForGene(@NotNull String gene) {
        return Lists.newArrayList(PatientReporterTestFactory.createTestEnrichedSomaticVariantBuilder().gene(gene).build());
    }
}