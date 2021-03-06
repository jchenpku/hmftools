package com.hartwig.hmftools.purple;

import static com.hartwig.hmftools.common.variant.structural.StructuralVariantFactory.CIPOS;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantFactory.INFERRED;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantFactory.RECOVERY_FILTER;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantFactory.RECOVERY_METHOD;
import static com.hartwig.hmftools.common.variant.structural.StructuralVariantFactory.SVTYPE;

import static htsjdk.variant.vcf.VCFHeaderLineCount.UNBOUNDED;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.hartwig.hmftools.common.purple.PurityAdjuster;
import com.hartwig.hmftools.common.purple.copynumber.PurpleCopyNumber;
import com.hartwig.hmftools.common.purple.segment.SegmentSupport;
import com.hartwig.hmftools.common.utils.Doubles;
import com.hartwig.hmftools.common.utils.collection.Multimaps;
import com.hartwig.hmftools.common.variant.enrich.StructuralRefContextEnrichment;
import com.hartwig.hmftools.common.variant.structural.CopyNumberEnrichedStructuralVariantFactory;
import com.hartwig.hmftools.common.variant.structural.EnrichedStructuralVariant;
import com.hartwig.hmftools.common.variant.structural.EnrichedStructuralVariantLeg;
import com.hartwig.hmftools.common.variant.structural.StructuralVariant;
import com.hartwig.hmftools.common.variant.structural.StructuralVariantFactory;
import com.hartwig.hmftools.purple.sv.VariantContextCollection;
import com.hartwig.hmftools.purple.sv.VariantContextCollectionDummy;
import com.hartwig.hmftools.purple.sv.VariantContextCollectionImpl;

import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.NotNull;

import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.index.tabix.TabixIndexCreator;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.Options;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFFilterHeaderLine;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;

class PurpleStructuralVariantSupplier {

    private static final String RECOVERED_DESC = "Entry has been recovered";
    private static final String RECOVERY_FILTER_DESC = "Filter before recovery";
    private static final String RECOVERY_METHOD_DESC =
            "Method used to recover, one of [UNBALANCED_SV_START, UNBALANCED_SV_END, UNSUPPORTED_BREAKEND_START, UNSUPPORTED_BREAKEND_END]";
    private static final String INFERRED_DESC = "Breakend inferred from copy number transition";
    private static final String IMPRECISE_DESC = "Imprecise structural variation";
    private static final String PURPLE_PLOIDY_INFO = "PURPLE_PLOIDY";
    private static final String PURPLE_PLOIDY_DESC = "Purity adjusted ploidy of variant";
    private static final String PURPLE_AF_INFO = "PURPLE_AF";
    private static final String PURPLE_AF_DESC = "Purity adjusted allele frequency at each breakend";
    private static final String PURPLE_CN_INFO = "PURPLE_CN";
    private static final String PURPLE_CN_DESC = "Purity adjusted copy number at each breakend";
    private static final String PURPLE_CN_CHANGE_INFO = "PURPLE_CN_CHANGE";
    private static final String PURPLE_CN_CHANGE_DESC = "Purity adjusted change in copy number at each breakend";
    private static final String CIPOS_DESC = "Confidence interval around POS for imprecise variants";
    private static final String SVTYPE_DESC = "Type of structural variant";

    private static final Allele REF_ALLELE = Allele.create("N", true);
    private static final Allele INCREASING_ALLELE = Allele.create(".N", false);
    private static final Allele DECREASING_ALLELE = Allele.create("N.", false);

    private final String outputVCF;
    private final Optional<VCFHeader> header;
    private final VariantContextCollection variants;
    private final String refGenomePath;

    private int counter = 0;

    PurpleStructuralVariantSupplier() {
        header = Optional.empty();
        outputVCF = Strings.EMPTY;
        variants = new VariantContextCollectionDummy();
        refGenomePath = Strings.EMPTY;
    }

    PurpleStructuralVariantSupplier(@NotNull final String version, @NotNull final String templateVCF, @NotNull final String outputVCF,
            @NotNull final String refGenomePath) {
        final VCFFileReader vcfReader = new VCFFileReader(new File(templateVCF), false);
        this.outputVCF = outputVCF;
        this.refGenomePath = refGenomePath;
        this.header = Optional.of(generateOutputHeader(version, vcfReader.getFileHeader()));
        this.variants = new VariantContextCollectionImpl(header.get());

        for (VariantContext context : vcfReader) {
            variants.add(context);
        }

        vcfReader.close();
    }

    void addVariant(@NotNull final VariantContext variantContext) {
        if (enabled()) {
            variants.add(variantContext);
        }
    }

    void inferMissingVariant(@NotNull final List<PurpleCopyNumber> copyNumbers) {
        if (enabled()) {
            for (int i = 1; i < copyNumbers.size(); i++) {
                PurpleCopyNumber copyNumber = copyNumbers.get(i);
                if (copyNumber.segmentStartSupport() == SegmentSupport.NONE) {
                    final PurpleCopyNumber prev = copyNumbers.get(i - 1);
                    variants.add(infer(copyNumber, prev));
                }
            }
        }
    }

    @NotNull
    private VariantContext infer(@NotNull final PurpleCopyNumber copyNumber, @NotNull final PurpleCopyNumber prev) {
        final long position;
        final Allele allele;
        if (Doubles.greaterThan(copyNumber.averageTumorCopyNumber(), prev.averageTumorCopyNumber())) {
            allele = INCREASING_ALLELE;
            position = copyNumber.start();
        } else {
            allele = DECREASING_ALLELE;
            position = copyNumber.start() - 1;
        }

        final Collection<Allele> alleles = Lists.newArrayList(REF_ALLELE, allele);

        long lowerRange = Math.min(-500, copyNumber.minStart() - copyNumber.start());
        long upperRange = Math.max(500, copyNumber.maxStart() - copyNumber.start());

        return new VariantContextBuilder("purple", copyNumber.chromosome(), position, copyNumber.start(), alleles).filter(INFERRED)
                .attribute(StructuralVariantFactory.IMPRECISE, true)
                .id("purple_" + counter++)
                .attribute(CIPOS, Lists.newArrayList(lowerRange, upperRange))
                .attribute(SVTYPE, "BND")
                .attribute(INFERRED, true)
                .noGenotypes()
                .make();
    }

    void write(@NotNull final PurityAdjuster purityAdjuster, @NotNull final List<PurpleCopyNumber> copyNumbers) throws IOException {
        if (header.isPresent()) {
            try (final IndexedFastaSequenceFile indexedFastaSequenceFile = new IndexedFastaSequenceFile(new File(refGenomePath));
                    final VariantContextWriter writer = new VariantContextWriterBuilder().setOutputFile(outputVCF)
                            .setReferenceDictionary(header.get().getSequenceDictionary())
                            .setIndexCreator(new TabixIndexCreator(header.get().getSequenceDictionary(), new TabixFormat()))
                            .setOutputFileType(VariantContextWriterBuilder.OutputType.BLOCK_COMPRESSED_VCF)
                            .setOption(Options.ALLOW_MISSING_FIELDS_IN_HEADER)
                            .build()) {

                final StructuralRefContextEnrichment refEnricher =
                        new StructuralRefContextEnrichment(indexedFastaSequenceFile, writer::add);
                writer.writeHeader(refEnricher.enrichHeader(header.get()));

                enriched(purityAdjuster, copyNumbers).forEach(refEnricher);
                refEnricher.flush();
            }
        }
    }

    @NotNull
    private Iterable<VariantContext> enriched(@NotNull final PurityAdjuster purityAdjuster,
            @NotNull final List<PurpleCopyNumber> copyNumbers) {
        assert (header.isPresent());

        final StructuralVariantFactory svFactory = new StructuralVariantFactory(x -> true);
        variants.forEach(svFactory::addVariantContext);

        final CopyNumberEnrichedStructuralVariantFactory svEnricher =
                new CopyNumberEnrichedStructuralVariantFactory(purityAdjuster, Multimaps.fromRegions(copyNumbers));

        final VariantContextCollectionImpl enrichedCollection = new VariantContextCollectionImpl(header.get());
        for (EnrichedStructuralVariant enrichedSV : svEnricher.enrich(svFactory.results())) {
            final VariantContext startContext = enrichedSV.startContext();
            if (startContext != null) {
                enrichedCollection.add(enrich(enrichedSV, startContext, false));
            }

            final VariantContext endContext = enrichedSV.endContext();
            if (endContext != null) {
                enrichedCollection.add(enrich(enrichedSV, endContext, true));
            }
        }

        svFactory.unmatched().forEach(enrichedCollection::add);
        return enrichedCollection;
    }

    @NotNull
    private VariantContext enrich(@NotNull final EnrichedStructuralVariant variant, @NotNull final VariantContext template,
            boolean reverse) {
        final List<Double> purpleAF = Lists.newArrayList();
        Optional.ofNullable(variant.start().adjustedAlleleFrequency()).ifPresent(purpleAF::add);
        Optional.ofNullable(variant.end()).map(EnrichedStructuralVariantLeg::adjustedAlleleFrequency).ifPresent(purpleAF::add);

        final List<Double> purpleCN = Lists.newArrayList();
        Optional.ofNullable(variant.start().adjustedCopyNumber()).ifPresent(purpleCN::add);
        Optional.ofNullable(variant.end()).map(EnrichedStructuralVariantLeg::adjustedCopyNumber).ifPresent(purpleCN::add);

        final List<Double> purpleCNChange = Lists.newArrayList();
        Optional.ofNullable(variant.start().adjustedCopyNumberChange()).ifPresent(purpleCNChange::add);
        Optional.ofNullable(variant.end()).map(EnrichedStructuralVariantLeg::adjustedCopyNumberChange).ifPresent(purpleCNChange::add);

        if (reverse) {
            Collections.reverse(purpleAF);
            Collections.reverse(purpleCN);
            Collections.reverse(purpleCNChange);
        }

        VariantContextBuilder builder = new VariantContextBuilder(template);
        if (!purpleAF.isEmpty()) {
            builder.attribute(PURPLE_AF_INFO, purpleAF);
        }

        if (!purpleCN.isEmpty()) {
            builder.attribute(PURPLE_CN_INFO, purpleCN);
        }

        if (!purpleCNChange.isEmpty()) {
            builder.attribute(PURPLE_CN_CHANGE_INFO, purpleCNChange);
        }

        Double ploidy = variant.ploidy();
        if (ploidy != null) {
            builder.attribute(PURPLE_PLOIDY_INFO, ploidy);
        }

        return builder.make();
    }

    @NotNull
    List<StructuralVariant> variants() {
        return variants.segmentationVariants();
    }

    @NotNull
    @VisibleForTesting
    static VCFHeader generateOutputHeader(@NotNull final String purpleVersion, @NotNull final VCFHeader template) {
        final VCFHeader outputVCFHeader = new VCFHeader(template.getMetaDataInInputOrder(), template.getGenotypeSamples());
        outputVCFHeader.addMetaDataLine(new VCFHeaderLine("purpleVersion", purpleVersion));

        outputVCFHeader.addMetaDataLine(VCFStandardHeaderLines.getFormatLine("GT"));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(StructuralVariantFactory.RECOVERED,
                0,
                VCFHeaderLineType.Flag,
                RECOVERED_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(StructuralVariantFactory.INFERRED, 0, VCFHeaderLineType.Flag, INFERRED_DESC));
        outputVCFHeader.addMetaDataLine(new VCFFilterHeaderLine(INFERRED, INFERRED_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(StructuralVariantFactory.IMPRECISE,
                0,
                VCFHeaderLineType.Flag,
                IMPRECISE_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(CIPOS, 2, VCFHeaderLineType.Integer, CIPOS_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(SVTYPE, 1, VCFHeaderLineType.String, SVTYPE_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(PURPLE_AF_INFO, UNBOUNDED, VCFHeaderLineType.Float, PURPLE_AF_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(PURPLE_CN_INFO, UNBOUNDED, VCFHeaderLineType.Float, PURPLE_CN_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(RECOVERY_METHOD, 1, VCFHeaderLineType.String, RECOVERY_METHOD_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(RECOVERY_FILTER, UNBOUNDED, VCFHeaderLineType.String, RECOVERY_FILTER_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(PURPLE_PLOIDY_INFO, 1, VCFHeaderLineType.Float, PURPLE_PLOIDY_DESC));
        outputVCFHeader.addMetaDataLine(new VCFInfoHeaderLine(PURPLE_CN_CHANGE_INFO,
                UNBOUNDED,
                VCFHeaderLineType.Float,
                PURPLE_CN_CHANGE_DESC));
        return outputVCFHeader;
    }

    private boolean enabled() {
        return header.isPresent();
    }
}
