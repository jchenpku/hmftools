package com.hartwig.hmftools.patientreporter.variants.germline;

import java.util.List;
import java.util.Set;

import com.google.common.collect.Lists;
import com.hartwig.hmftools.common.chord.ChordAnalysis;
import com.hartwig.hmftools.common.drivercatalog.DriverCategory;
import com.hartwig.hmftools.common.purple.gene.GeneCopyNumber;
import com.hartwig.hmftools.common.variant.SomaticVariant;
import com.hartwig.hmftools.patientreporter.cfreport.chapters.GenomicAlterationsChapter;
import com.hartwig.hmftools.patientreporter.variants.driver.DriverGeneView;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public final class FilterGermlineVariants {
    private static final Logger LOGGER = LogManager.getLogger(FilterGermlineVariants.class);

    private FilterGermlineVariants() {
    }

    @NotNull
    public static List<InterpretGermlineVariant> filterGermlineVariantsForReporting(List<GermlineVariant> germlineVariants,
            @NotNull DriverGeneView driverGeneView, @NotNull GermlineReportingModel germlineReportingModel,
            @NotNull List<GeneCopyNumber> allGeneCopyNumbers, @NotNull List<SomaticVariant> variantsToReport, @NotNull ChordAnalysis chordAnalysis) {
        List<InterpretGermlineVariant> filteredGermlineVariants = Lists.newArrayList();

        Set<String> reportingGermlineGenes = germlineReportingModel.reportableGermlineGenes();
        for (GermlineVariant germlineVariant : germlineVariants) {
            assert germlineVariant.passFilter();

            if (reportingGermlineGenes.contains(germlineVariant.gene())) {

                if ( chordAnalysis.hrdValue() >= 0.5) {
                    // report all reportable germline variants where HRD score is greater than 0.5
                    filteredGermlineVariants.add(mergeInterpretGermlineVariants(germlineVariant, 0));
                } else {
                    // Note: Reporting germline genes may not necessarily be present in driverGeneView!
                    if (driverGeneView.category(germlineVariant.gene()) == DriverCategory.ONCO) {
                        // Report all germline variants on reportable oncogenes.
                        filteredGermlineVariants.add(mergeInterpretGermlineVariants(germlineVariant, 1));
                    } else {
                        // Only report germline variants on TSGs if there is a 2nd hit.
                        boolean filterBiallelic = germlineVariant.biallelic();

                        boolean filterMinCopyNumberTumor = false;
                        GeneCopyNumber geneCopyNumber = lookupGeneCopyNumber(allGeneCopyNumbers, germlineVariant.gene());
                        if (Math.round(geneCopyNumber.minCopyNumber()) <= 1 && (Math.round(germlineVariant.adjustedCopyNumber()) >= 2)) {
                            filterMinCopyNumberTumor = true;
                        }

                        boolean filterSomaticVariantInSameGene = false;
                        for (SomaticVariant variant : variantsToReport) {
                            if (variant.gene().equals(germlineVariant.gene())) {
                                filterSomaticVariantInSameGene = true;
                            }
                        }

                        if (filterBiallelic || filterMinCopyNumberTumor || filterSomaticVariantInSameGene) {
                            filteredGermlineVariants.add(mergeInterpretGermlineVariants(germlineVariant, 0));
                        }
                    }
                }
            }
        }
        return filteredGermlineVariants;
    }

    @NotNull
    private static InterpretGermlineVariant mergeInterpretGermlineVariants(@NotNull GermlineVariant germlineVariant, double driverLikelihood) {
        return ImmutableInterpretGermlineVariant.builder().germlineVariant(germlineVariant).driverLikelihood(driverLikelihood).build();
    }

    @NotNull
    private static GeneCopyNumber lookupGeneCopyNumber(@NotNull List<GeneCopyNumber> allGeneCopyNumbers, @NotNull String gene) {
        for (GeneCopyNumber geneCopyNumber : allGeneCopyNumbers) {
            if (geneCopyNumber.gene().equals(gene)) {
                return geneCopyNumber;
            }
        }

        throw new IllegalStateException("Could not find gene copy number for gene: " + gene);
    }
}
