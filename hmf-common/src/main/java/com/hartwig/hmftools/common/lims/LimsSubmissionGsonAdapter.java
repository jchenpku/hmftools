package com.hartwig.hmftools.common.lims;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.jetbrains.annotations.NotNull;

final class LimsSubmissionGsonAdapter {

    private LimsSubmissionGsonAdapter() {
    }

    @NotNull
    static Gson buildGsonSubmission() {
        return new GsonBuilder().registerTypeAdapterFactory(new GsonAdaptersLimsJsonDataSubmission()).create();
    }
}
