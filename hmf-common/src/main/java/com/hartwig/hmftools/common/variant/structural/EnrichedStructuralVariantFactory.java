package com.hartwig.hmftools.common.variant.structural;

import java.util.List;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.hartwig.hmftools.common.chromosome.Chromosome;
import com.hartwig.hmftools.common.purple.PurityAdjuster;
import com.hartwig.hmftools.common.purple.copynumber.PurpleCopyNumber;
import com.hartwig.hmftools.common.purple.copynumber.sv.StructuralVariantLegPloidy;
import com.hartwig.hmftools.common.purple.copynumber.sv.StructuralVariantLegPloidyFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.samtools.reference.ReferenceSequence;

public final class EnrichedStructuralVariantFactory {

    private static final int DISTANCE = 10;

    private final PurityAdjuster purityAdjuster;
    private final IndexedFastaSequenceFile reference;
    private final Multimap<Chromosome, PurpleCopyNumber> copyNumbers;

    public EnrichedStructuralVariantFactory(@NotNull final IndexedFastaSequenceFile reference, @NotNull final PurityAdjuster purityAdjuster,
            @NotNull final Multimap<Chromosome, PurpleCopyNumber> copyNumbers) {
        this.reference = reference;
        this.purityAdjuster = purityAdjuster;
        this.copyNumbers = copyNumbers;
    }

    @NotNull
    public List<EnrichedStructuralVariant> enrich(@NotNull final List<StructuralVariant> variants) {
        final StructuralVariantLegPloidyFactory<PurpleCopyNumber> ploidyFactory =
                new StructuralVariantLegPloidyFactory<>(purityAdjuster, PurpleCopyNumber::averageTumorCopyNumber);

        final List<EnrichedStructuralVariant> result = Lists.newArrayList();
        for (final StructuralVariant variant : variants) {
            ImmutableEnrichedStructuralVariant.Builder builder = ImmutableEnrichedStructuralVariant.builder().from(variant);
            ImmutableEnrichedStructuralVariantLeg.Builder startBuilder = createBuilder(variant.start());
            // Every SV should have a start, while end is optional.
            assert startBuilder != null;
            ImmutableEnrichedStructuralVariantLeg.Builder endBuilder = createBuilder(variant.end());

            List<StructuralVariantLegPloidy> ploidies = ploidyFactory.create(variant, copyNumbers);
            if (!ploidies.isEmpty()) {
                // The implied ploidy should be equal between start and end, so doesn't matter what we pick.
                builder.ploidy(round(ploidies.get(0).averageImpliedPloidy()));

                StructuralVariantLegPloidy start = ploidies.get(0);
                StructuralVariantLegPloidy end = ploidies.size() <= 1 ? null : ploidies.get(1);

                startBuilder.adjustedAlleleFrequency(round(start.adjustedVaf()));
                startBuilder.adjustedCopyNumber(round(start.adjustedCopyNumber()));
                startBuilder.adjustedCopyNumberChange(round(start.adjustedCopyNumberChange()));

                if (end != null) {
                    assert endBuilder != null;
                    endBuilder.adjustedAlleleFrequency(round(end.adjustedVaf()));
                    endBuilder.adjustedCopyNumber(round(end.adjustedCopyNumber()));
                    endBuilder.adjustedCopyNumberChange(round(end.adjustedCopyNumberChange()));
                }
            }

            result.add(builder.start(startBuilder.build()).end(endBuilder == null ? null : endBuilder.build()).build());
        }

        return result;
    }

    @Nullable
    private ImmutableEnrichedStructuralVariantLeg.Builder createBuilder(@Nullable StructuralVariantLeg leg) {
        if (leg == null) {
            return null;
        }

        final ImmutableEnrichedStructuralVariantLeg.Builder builder = ImmutableEnrichedStructuralVariantLeg.builder().from(leg);
        builder.refGenomeContext(context(leg.chromosome(), leg.position()));
        return builder;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    @NotNull
    private String context(@NotNull String chromosome, long position) {
        final int chromosomeLength = reference.getSequenceDictionary().getSequence(chromosome).getSequenceLength();
        final ReferenceSequence sequence =
                reference.getSubsequenceAt(chromosome, Math.max(1, position - DISTANCE), Math.min(position + DISTANCE, chromosomeLength));
        return sequence.getBaseString();
    }
}
