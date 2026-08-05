package com.senp.qa.smoke.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class SmokeInstrumentation extends Instrumentation {
    private static final String TAG = "SENP_QA_SMOKE";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        long started = SystemClock.elapsedRealtime();
        Bundle results = new Bundle();
        try {
            Context target = getTargetContext();
            verifyTimestampContract();
            JSONObject payload = new JSONObject();
            payload.put("schema_version", 1);
            payload.put("ok", true);
            payload.put("package", target.getPackageName());
            payload.put("api", Build.VERSION.SDK_INT);
            payload.put("abi", Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]);
            payload.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            payload.put("peak_pss_bytes", (long) Debug.getPss() * 1024L);
            payload.put("runtime_heap_bytes", Runtime.getRuntime().totalMemory());
            JSONArray checks = new JSONArray();
            checks.put("target_context_available");
            checks.put("timestamp_contract_monotonic");
            checks.put("external_artifact_writable");
            payload.put("checks", checks);

            File external = target.getExternalFilesDir(null);
            if (external == null && !target.getFilesDir().exists()) {
                throw new IllegalStateException("No writable target storage directory");
            }
            File directory = external != null ? external : target.getFilesDir();
            File artifact = new File(directory, "senp-qa-smoke.json");
            try (FileOutputStream stream = new FileOutputStream(artifact, false)) {
                stream.write((payload.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            Log.i(TAG, "SENP_QA_RESULT ok=true artifact=" + artifact.getAbsolutePath());
            results.putString("stream", "Senp QA smoke passed; artifact=" + artifact.getAbsolutePath() + "\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable failure) {
            Log.e(TAG, "SENP_QA_RESULT ok=false", failure);
            results.putString("shortMsg", failure.getClass().getName());
            results.putString("longMsg", String.valueOf(failure.getMessage()));
            results.putString("stream", "Senp QA smoke failed: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private static void verifyTimestampContract() {
        long[] timestampsMs = {0L, 67L, 133L, 200L};
        for (int index = 1; index < timestampsMs.length; index++) {
            if (timestampsMs[index] <= timestampsMs[index - 1]) {
                throw new AssertionError("Timestamps are not strictly monotonic");
            }
        }
    }
}
