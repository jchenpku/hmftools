package com.hartwig.hmftools.patientreporter.cfreport.chapters;

import com.hartwig.hmftools.patientreporter.AnalysedPatientReport;
import com.hartwig.hmftools.patientreporter.cfreport.ReportResources;
import com.hartwig.hmftools.patientreporter.cfreport.components.TableUtil;
import com.itextpdf.io.IOException;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.property.HorizontalAlignment;
import com.itextpdf.layout.property.UnitValue;
import org.jetbrains.annotations.NotNull;

import java.net.MalformedURLException;

public class CircosChapter implements ReportChapter {

    private final AnalysedPatientReport patientReport;

    public CircosChapter(@NotNull final AnalysedPatientReport patientReport) {
        this.patientReport = patientReport;
    }

    @NotNull
    @Override
    public String getName() {
        return "CIRCOS plot";
    }

    @Override
    public final void render(@NotNull final Document reportDocument) throws IOException {

        // Add Circos plot
        final String circosPath = patientReport.circosPath();
        try {
            final Image circosImage = new Image(ImageDataFactory.create(circosPath));
            circosImage.setMaxHeight(400);
            circosImage.setHorizontalAlignment(HorizontalAlignment.CENTER);
            circosImage.setMarginBottom(8);
            reportDocument.add(circosImage);
        } catch (MalformedURLException e) {
            throw new IOException("Failed to read circos plot image at " + circosPath);
        }

        // Explanation
        Table table = new Table(UnitValue.createPercentArray(new float[] {10, 1, 10, 1, 10}));
        table.setWidth(getContentWidth());

        table.addCell(TableUtil.getLayoutCell().add(new Div()
            .add(createContentParagraph("The outer first circle", " shows the chromosomes. The darker " +
                    "shaded areas represent large gaps in the human reference genome: i.e. regions of centromeres, " +
                    "heterochromatin & missing short arms."))
            .add(createContentParagraph("The second circle", " shows the somatic variants (incl. exon, " +
                    "intron and intergenic regions). Somatic variants are further divided into an outer ring of single " +
                    "nucleotide polymorphism (SNP) allele frequencies and an inner ring of short insertion/deletion (INDEL) " +
                    "locations. SNP allele frequencies have been corrected for tumor purity and scale from 0 to 100%. Each " +
                    "dot represents a single somatic variant. SNPs are colored according to the type of base change (e.g. " +
                    "C>T/G>A in red) and are in concordance with the coloring used in Alexandrov et al. 2013 Nature paper " +
                    "that describes the use of mutational signatures. INDELs are colored yellow and red for insertions " +
                    "and deletions respectively."))));

        table.addCell(TableUtil.getLayoutCell()); // Spacer

        table.addCell(TableUtil.getLayoutCell().add(new Div()
            .add(createContentParagraph("The third circle", " shows all observed tumor purity adjusted " +
                    "copy number changes,including both focal and chromosomal somatic events. Copy number losses are " +
                    "indicated in red, green shows regions of copy number gain. The scale ranges from 0 (complete loss) " +
                    "to 6 (high level gains). If the absolute copy number is > 6 it is shown as 6 with a green dot on " +
                    "the diagram.")
            .add(createContentParagraph("The fourth circle", " represents the observed 'minor allele " +
                    "copy numbers’ across the chromosome. The range of the chart is from 0 to 3. The expected normal " +
                    "minor allele copy number is 1, and anything below 1 is shown as a loss (orange) and represents a " +
                    "LOH event. Minor allele copy numbers above 1 (blue) indicate amplification events of both A and B " +
                    "alleles at the indicated locations.")))));

        table.addCell(TableUtil.getLayoutCell()); // Spacer

        table.addCell(TableUtil.getLayoutCell().add(new Div()
                .add(createContentParagraph("The innermost circle", " displays the observed structural " +
                        "variants within or between the chromosomes. Translocations are indicated in blue, deletions in " +
                        "red, insertions in yellow, tandem duplications in green and inversions in black."))));

        reportDocument.add(table);

    }

    @NotNull
    private static Paragraph createContentParagraph(@NotNull String boldPart, @NotNull String regularPart) {
        return new Paragraph(boldPart)
            .addStyle(ReportResources.subTextBoldStyle())
                .setFixedLeading(ReportResources.BODY_TEXT_LEADING)
            .add(new Text(regularPart)
                .addStyle(ReportResources.subTextStyle()))
                .setFixedLeading(ReportResources.BODY_TEXT_LEADING);
    }

}