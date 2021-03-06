package com.hartwig.hmftools.common.lims;

import static com.hartwig.hmftools.common.lims.LimsTestUtil.createLimsSampleDataBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

public class LimsTest {

    private static final String REF_SAMPLE_BARCODE = "FR1234";
    private static final String TUMOR_SAMPLE_BARCODE = "FR1";
    private static final String TUMOR_SAMPLE_ID = "CPCT02991111T";
    private static final String SUBMISSION = "ABCDEF123";

    @Test
    public void canReadProperlyDefinedSample() {
        String patientId = "CPCT02991111";
        String arrivalDate = "2017-05-01";
        String samplingDate = "2017-04-15";
        String dnaConcentration = "10";
        String purityShallowSeq = "0.71";
        String primaryTumor = "Prostate";
        String labSopVersions = "PREP1V2-QC1V2-SEQ1V2";
        String projectName = "projectX";
        String requesterEmail = "henk@hmf.nl";
        String requesterName = "henk";
        String hospitalPatientId = "Henkie";
        String hospitalPathologySampleId = "Henkie's sample";

        LimsJsonSampleData sampleData = createLimsSampleDataBuilder().sampleId(TUMOR_SAMPLE_ID)
                .patientId(patientId)
                .arrivalDate(arrivalDate)
                .samplingDate(samplingDate)
                .dnaConcentration(dnaConcentration)
                .primaryTumor(primaryTumor)
                .labSopVersions(labSopVersions)
                .submission(SUBMISSION)
                .refBarcode(REF_SAMPLE_BARCODE)
                .tumorBarcode(TUMOR_SAMPLE_BARCODE)
                .requesterEmail(requesterEmail)
                .requesterName(requesterName)
                .shallowSeq("1")
                .germlineReportingChoice(Strings.EMPTY)
                .hospitalPatientId(hospitalPatientId)
                .hospitalPathologySampleId(hospitalPathologySampleId)
                .build();

        LimsJsonSubmissionData submissionData =
                ImmutableLimsJsonSubmissionData.builder().submission(SUBMISSION).projectName(projectName).build();

        LimsShallowSeqData shallowSeqData = ImmutableLimsShallowSeqData.builder()
                .sampleBarcode(TUMOR_SAMPLE_BARCODE)
                .sampleId(TUMOR_SAMPLE_ID)
                .purityShallowSeq(purityShallowSeq)
                .hasReliableQuality(true)
                .hasReliablePurity(true)
                .build();

        Lims lims = buildFullTestLims(sampleData, submissionData, shallowSeqData);

        assertEquals(1, lims.sampleBarcodeCount());
        assertEquals(1, lims.sampleBarcodes().size());
        assertFalse(lims.confirmedToHaveNoSamplingDate(TUMOR_SAMPLE_ID));

        lims.validateSampleBarcodeCombination(REF_SAMPLE_BARCODE, Strings.EMPTY, TUMOR_SAMPLE_BARCODE, TUMOR_SAMPLE_ID);

        assertEquals(patientId, lims.patientId(TUMOR_SAMPLE_BARCODE));
        assertEquals(TUMOR_SAMPLE_ID, lims.sampleId(TUMOR_SAMPLE_BARCODE));
        assertEquals(LimsTestUtil.toDate(samplingDate), lims.samplingDate(TUMOR_SAMPLE_BARCODE));
        assertEquals(LimsTestUtil.toDate(arrivalDate), lims.arrivalDate(TUMOR_SAMPLE_BARCODE, TUMOR_SAMPLE_ID));

        assertEquals(SUBMISSION, lims.submissionId(TUMOR_SAMPLE_BARCODE));
        assertEquals(projectName, lims.projectName(TUMOR_SAMPLE_BARCODE));
        assertEquals(requesterEmail, lims.requesterEmail(TUMOR_SAMPLE_BARCODE));
        assertEquals(requesterName, lims.requesterName(TUMOR_SAMPLE_BARCODE));

        Integer dnaAmount = lims.dnaNanograms(TUMOR_SAMPLE_BARCODE);
        assertNotNull(dnaAmount);
        assertEquals(500L, (int) dnaAmount);

        assertEquals("71%", lims.purityShallowSeq(TUMOR_SAMPLE_BARCODE));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.pathologyTumorPercentage(TUMOR_SAMPLE_BARCODE));
        assertEquals(primaryTumor, lims.primaryTumor(TUMOR_SAMPLE_BARCODE));
        assertEquals(labSopVersions, lims.labProcedures(TUMOR_SAMPLE_BARCODE));

        assertEquals(hospitalPatientId, lims.hospitalPatientId(TUMOR_SAMPLE_BARCODE));
        assertEquals(hospitalPathologySampleId, lims.hospitalPathologySampleId(TUMOR_SAMPLE_BARCODE));
        assertEquals(LimsGermlineReportingChoice.UNKNOWN, lims.germlineReportingChoice(TUMOR_SAMPLE_BARCODE));
    }

    @Test
    public void worksForNonExistingSamplesAndSubmissions() {
        Lims lims = LimsFactory.empty();
        String doesNotExistSample = "DoesNotExist";

        lims.validateSampleBarcodeCombination(doesNotExistSample, doesNotExistSample, doesNotExistSample, doesNotExistSample);
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.patientId(doesNotExistSample));
        assertNull(lims.arrivalDate(doesNotExistSample, doesNotExistSample));
        assertNull(lims.samplingDate(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.requesterName(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.requesterEmail(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.projectName(doesNotExistSample));
        assertNull(lims.dnaNanograms(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.pathologyTumorPercentage(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.purityShallowSeq(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.primaryTumor(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.labProcedures(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.hospitalPatientId(doesNotExistSample));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.hospitalPathologySampleId(doesNotExistSample));
        assertEquals(LimsGermlineReportingChoice.UNKNOWN, lims.germlineReportingChoice(doesNotExistSample));
    }

    @Test
    public void fallBackOnPreLIMSArrivalDateWorks() {
        LocalDate date = LimsTestUtil.toDate("2017-10-03");

        Lims lims = buildTestLimsWithPreLimsArrivalDateForSampleId(TUMOR_SAMPLE_ID, date);

        assertEquals(date, lims.arrivalDate("DoesNotExist", TUMOR_SAMPLE_ID));
    }

    @Test
    public void invalidDataYieldsNullOrNA() {
        LimsJsonSampleData sampleData = createLimsSampleDataBuilder().sampleId(TUMOR_SAMPLE_ID)
                .tumorBarcode(TUMOR_SAMPLE_BARCODE)
                .arrivalDate("IsNotADate")
                .samplingDate(null)
                .dnaConcentration("IsNotADNAConcentration")
                .pathologyTumorPercentage("IsNotANumber")
                .labSopVersions("anything")
                .build();

        Lims lims = buildTestLimsWithSample(sampleData);

        assertEquals(1, lims.sampleBarcodeCount());

        assertNull(lims.arrivalDate(TUMOR_SAMPLE_BARCODE, TUMOR_SAMPLE_ID));
        assertNull(lims.samplingDate(TUMOR_SAMPLE_BARCODE));
        assertNull(lims.dnaNanograms(TUMOR_SAMPLE_BARCODE));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.pathologyTumorPercentage(TUMOR_SAMPLE_BARCODE));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims.labProcedures(TUMOR_SAMPLE_BARCODE));
    }

    @Test
    public void missingOrMalformedShallowSeqDataForSampleYieldsNA() {
        LimsJsonSampleData sampleData1 =
                createLimsSampleDataBuilder().tumorBarcode(TUMOR_SAMPLE_BARCODE).sampleId(TUMOR_SAMPLE_ID).shallowSeq("1").build();

        Lims lims1 = buildTestLimsWithSample(sampleData1);
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims1.purityShallowSeq(TUMOR_SAMPLE_BARCODE));

        LimsJsonSampleData sampleData2 =
                createLimsSampleDataBuilder().tumorBarcode(TUMOR_SAMPLE_BARCODE).sampleId(TUMOR_SAMPLE_ID).shallowSeq("1").build();
        Lims lims2 = buildTestLimsWithSampleAndShallowSeq(sampleData2, "NotANumber", true, true);
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims2.purityShallowSeq(TUMOR_SAMPLE_BARCODE));
    }

    @Test
    public void canRetrievePathologyPercentageForSample() {
        LimsJsonSampleData sampleData1 = createLimsSampleDataBuilder().tumorBarcode(TUMOR_SAMPLE_BARCODE)
                .sampleId(TUMOR_SAMPLE_ID)
                .shallowSeq("0")
                .pathologyTumorPercentage("70")
                .build();

        Lims lims1 = buildTestLimsWithSample(sampleData1);
        assertEquals(Lims.NOT_DETERMINED_STRING, lims1.purityShallowSeq(TUMOR_SAMPLE_BARCODE));
        assertEquals("70%", lims1.pathologyTumorPercentage(TUMOR_SAMPLE_BARCODE));

        LimsJsonSampleData sampleData2 = createLimsSampleDataBuilder().tumorBarcode(TUMOR_SAMPLE_BARCODE)
                .sampleId(TUMOR_SAMPLE_ID)
                .shallowSeq("0")
                .pathologyTumorPercentage("NotANumber")
                .build();

        Lims lims2 = buildTestLimsWithSample(sampleData2);
        assertEquals(Lims.NOT_DETERMINED_STRING, lims2.purityShallowSeq(TUMOR_SAMPLE_BARCODE));
        assertEquals(Lims.NOT_AVAILABLE_STRING, lims2.pathologyTumorPercentage(TUMOR_SAMPLE_BARCODE));
    }

    @Test
    public void canRetrieveShallowSeqPurityForSample() {
        LimsJsonSampleData sampleData =
                createLimsSampleDataBuilder().tumorBarcode(TUMOR_SAMPLE_BARCODE).sampleId(TUMOR_SAMPLE_ID).shallowSeq("1").build();

        Lims lims = buildTestLimsWithSampleAndShallowSeq(sampleData, "0.2", false, true);

        assertEquals("20%", lims.purityShallowSeq(TUMOR_SAMPLE_BARCODE));
    }

    @Test
    public void canRetrieveShallowSeqBelowDetectionLimitForSample() {
        LimsJsonSampleData sampleData =
                createLimsSampleDataBuilder().tumorBarcode(TUMOR_SAMPLE_BARCODE).sampleId(TUMOR_SAMPLE_ID).shallowSeq("1").build();

        Lims lims = buildTestLimsWithSampleAndShallowSeq(sampleData, "0.10", true, false);
        assertEquals("below detection threshold", lims.purityShallowSeq(TUMOR_SAMPLE_BARCODE));
    }

    @NotNull
    private static Lims buildFullTestLims(@NotNull LimsJsonSampleData sampleData, @NotNull LimsJsonSubmissionData submissionData,
            @NotNull LimsShallowSeqData shallowSeqData) {
        Map<String, LimsJsonSampleData> dataPerSampleBarcode = Maps.newHashMap();
        dataPerSampleBarcode.put(sampleData.tumorBarcode(), sampleData);

        Map<String, LimsJsonSubmissionData> dataPerSubmission = Maps.newHashMap();
        dataPerSubmission.put(submissionData.submission(), submissionData);

        Map<String, LimsShallowSeqData> shallowSeqDataPerSampleBarcode = Maps.newHashMap();
        shallowSeqDataPerSampleBarcode.put(shallowSeqData.sampleBarcode(), shallowSeqData);

        Map<String, LocalDate> preLimsArrivalDatesPerSampleId = Maps.newHashMap();
        Set<String> sampleIdsWithoutSamplingDates = Sets.newHashSet();
        Set<String> blacklistedPatients = Sets.newHashSet();

        return new Lims(dataPerSampleBarcode,
                dataPerSubmission,
                shallowSeqDataPerSampleBarcode,
                preLimsArrivalDatesPerSampleId,
                sampleIdsWithoutSamplingDates,
                blacklistedPatients);
    }

    @NotNull
    private static Lims buildTestLimsWithSample(@NotNull LimsJsonSampleData sampleData) {
        Map<String, LimsJsonSampleData> dataPerSampleBarcode = Maps.newHashMap();
        dataPerSampleBarcode.put(sampleData.tumorBarcode(), sampleData);
        Map<String, LimsJsonSubmissionData> dataPerSubmission = Maps.newHashMap();
        Map<String, LimsShallowSeqData> shallowSeqDataPerSampleBarcode = Maps.newHashMap();
        Map<String, LocalDate> preLimsArrivalDatesPerSampleId = Maps.newHashMap();
        Set<String> sampleIdsWithoutSamplingDate = Sets.newHashSet();
        Set<String> blacklistedPatients = Sets.newHashSet();

        return new Lims(dataPerSampleBarcode,
                dataPerSubmission,
                shallowSeqDataPerSampleBarcode,
                preLimsArrivalDatesPerSampleId,
                sampleIdsWithoutSamplingDate,
                blacklistedPatients);
    }

    @NotNull
    private static Lims buildTestLimsWithSampleAndShallowSeq(@NotNull LimsJsonSampleData sampleData, @NotNull String shallowSeqPurity,
            boolean hasReliableQuality, boolean hasReliablePurity) {
        Map<String, LimsJsonSampleData> dataPerSampleBarcode = Maps.newHashMap();
        dataPerSampleBarcode.put(sampleData.tumorBarcode(), sampleData);

        Map<String, LimsJsonSubmissionData> dataPerSubmission = Maps.newHashMap();
        Map<String, LimsShallowSeqData> shallowSeqDataPerSampleBarcode = Maps.newHashMap();
        shallowSeqDataPerSampleBarcode.put(sampleData.tumorBarcode(),
                ImmutableLimsShallowSeqData.of("FR1", sampleData.sampleId(), shallowSeqPurity, hasReliableQuality, hasReliablePurity));

        Map<String, LocalDate> preLimsArrivalDatesPerSampleId = Maps.newHashMap();
        Set<String> sampleIdsWithoutSamplingDate = Sets.newHashSet();
        Set<String> blacklistedPatients = Sets.newHashSet();

        return new Lims(dataPerSampleBarcode,
                dataPerSubmission,
                shallowSeqDataPerSampleBarcode,
                preLimsArrivalDatesPerSampleId,
                sampleIdsWithoutSamplingDate,
                blacklistedPatients);
    }

    @NotNull
    private static Lims buildTestLimsWithPreLimsArrivalDateForSampleId(@NotNull String sampleId, @NotNull LocalDate date) {
        final Map<String, LimsJsonSampleData> dataPerSampleBarcode = Maps.newHashMap();
        final Map<String, LimsJsonSubmissionData> dataPerSubmission = Maps.newHashMap();
        Map<String, LimsShallowSeqData> shallowSeqDataPerSampleBarcode = Maps.newHashMap();

        final Map<String, LocalDate> preLimsArrivalDatesPerSampleId = Maps.newHashMap();
        preLimsArrivalDatesPerSampleId.put(sampleId, date);

        Set<String> sampleIdsWithoutSamplingDate = Sets.newHashSet();
        Set<String> blacklistedPatients = Sets.newHashSet();

        return new Lims(dataPerSampleBarcode,
                dataPerSubmission,
                shallowSeqDataPerSampleBarcode,
                preLimsArrivalDatesPerSampleId,
                sampleIdsWithoutSamplingDate,
                blacklistedPatients);
    }
}