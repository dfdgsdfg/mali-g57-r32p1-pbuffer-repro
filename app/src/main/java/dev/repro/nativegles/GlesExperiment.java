package dev.repro.nativegles;

import org.json.JSONObject;

interface GlesExperiment {
    void start();
    void stop();
    JSONObject snapshot();
}
