package com.hartwig.hmftools.common.drivercatalog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Maps;
import com.hartwig.hmftools.common.drivercatalog.dnds.DndsDriverGeneLikelihoodSupplier;
import com.hartwig.hmftools.common.purple.gene.GeneCopyNumber;

import org.jetbrains.annotations.NotNull;

public class CNADrivers {

    private static final double MIN_COPY_NUMBER_RELATIVE_INCREASE = 3;
    private static final double MAX_COPY_NUMBER_DEL = 0.5;

    @NotNull
    private final Set<String> oncoGenes;
    @NotNull
    private final Set<String> tsGenes;
    @NotNull
    private final Set<String> amplificationTargets;
    @NotNull
    private final Set<String> deletionTargets;
    @NotNull
    private final Map<String, String> deletionBandMap;

    @NotNull
    public static Set<String> reportableGeneDeletions() {
        return deletionTargets().keySet();
    }

    @NotNull
    private static Set<String> amplificationTargets() {
        final InputStream inputStream = DndsDriverGeneLikelihoodSupplier.class.getResourceAsStream("/cna/AmplificationTargets.tsv");
        return new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.toSet());
    }

    @NotNull
    private static Map<String, String> deletionTargets() {
        final Map<String, String> result = Maps.newHashMap();
        final InputStream inputStream = DndsDriverGeneLikelihoodSupplier.class.getResourceAsStream("/cna/DeletionTargets.tsv");
        new BufferedReader(new InputStreamReader(inputStream)).lines().forEach(line -> {
            final String[] values = line.split("\t");
            result.put(values[0], values[1]);
        });

        return result;
    }

    public CNADrivers() {
        this.oncoGenes = DndsDriverGeneLikelihoodSupplier.oncoLikelihood().keySet();
        this.tsGenes = DndsDriverGeneLikelihoodSupplier.tsgLikelihood().keySet();

        Map<String, String> rawMap = deletionTargets();
        this.deletionTargets = rawMap.keySet();
        this.deletionBandMap = rawMap.entrySet()
                .stream()
                .filter(x -> !x.getValue().equals("NA"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        this.amplificationTargets = amplificationTargets();
    }

    @NotNull
    public List<DriverCatalog> amplifications(final double ploidy, @NotNull final List<GeneCopyNumber> geneCopyNumbers) {
        return geneCopyNumbers.stream()
                .filter(x -> x.minCopyNumber() / ploidy > MIN_COPY_NUMBER_RELATIVE_INCREASE)
                .filter(x -> oncoGenes.contains(x.gene()) | amplificationTargets.contains(x.gene()))
                .map(x -> ImmutableDriverCatalog.builder()
                        .chromosome(x.chromosome())
                        .chromosomeBand(x.chromosomeBand())
                        .gene(x.gene())
                        .missense(0)
                        .nonsense(0)
                        .inframe(0)
                        .frameshift(0)
                        .splice(0)
                        .dndsLikelihood(0)
                        .driverLikelihood(1)
                        .driver(DriverType.AMP)
                        .likelihoodMethod(LikelihoodMethod.AMP)
                        .category(tsGenes.contains(x.gene()) ? DriverCategory.TSG : DriverCategory.ONCO)
                        .biallelic(false)
                        .minCopyNumber(x.minCopyNumber())
                        .maxCopyNumber(x.maxCopyNumber())
                        .build())
                .collect(Collectors.toList());
    }

    @NotNull
    public List<DriverCatalog> deletions(@NotNull final List<GeneCopyNumber> geneCopyNumbers) {
        return geneCopyNumbers.stream()
                .filter(x -> x.minCopyNumber() < MAX_COPY_NUMBER_DEL)
                .filter(x -> tsGenes.contains(x.gene()) | deletionTargets.contains(x.gene()))
                .map(x -> ImmutableDriverCatalog.builder()
                        .chromosome(x.chromosome())
                        .chromosomeBand(deletionBandMap.getOrDefault(x.gene(), x.chromosomeBand()))
                        .gene(x.gene())
                        .missense(0)
                        .nonsense(0)
                        .inframe(0)
                        .frameshift(0)
                        .splice(0)
                        .dndsLikelihood(0)
                        .driverLikelihood(1)
                        .driver(DriverType.DEL)
                        .likelihoodMethod(LikelihoodMethod.DEL)
                        .category(oncoGenes.contains(x.gene()) ? DriverCategory.ONCO : DriverCategory.TSG)
                        .biallelic(true)
                        .minCopyNumber(x.minCopyNumber())
                        .maxCopyNumber(x.maxCopyNumber())
                        .build())
                .collect(Collectors.toList());
    }
}
