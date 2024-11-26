/*
 * Copyright (c) 2024, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.psiphon3.log.MyLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PackageHelper {

    private static final String SIGNATURES_JSON_FILE = "trusted_signatures.json";

    // Unmodifiable map of trusted packages with their corresponding sets of SHA-256 signature hashes
    private static final Map<String, Set<String>> TRUSTED_PACKAGES;
    static {
        // Psiphon Conduit package and its signature as SHA-256 hash using uppercase hex encoding, continuous (no separator)
        Map<String, Set<String>> map = new HashMap<>();
        map.put("ca.psiphon.conduit", new HashSet<>(Arrays.asList(
                "48:8C:4B:47:90:2E:CB:48:04:7E:97:FF:FE:1F:19:C4:B0:0F:31:40:D2:E2:57:06:70:95:23:CF:FE:4D:C4:B3"
                // Add additional valid signatures for the package as needed:
                //"THEOTHERSIGNATUREHASHHERE"
        )));
        TRUSTED_PACKAGES = Collections.unmodifiableMap(map);
    }

    private static final ConcurrentHashMap<String, Set<String>> RUNTIME_TRUSTED_PACKAGES = new ConcurrentHashMap<>();

    // Get the expected signature for a package
    @NonNull
    public static Set<String> getExpectedSignaturesForPackage(String packageName) {
        Set<String> signatures = new HashSet<>();
        Set<String> trustedSigs = TRUSTED_PACKAGES.get(packageName);
        if (trustedSigs != null) {
            signatures.addAll(trustedSigs);
        }
        Set<String> runtimeSigs = RUNTIME_TRUSTED_PACKAGES.get(packageName);
        if (runtimeSigs != null) {
            signatures.addAll(runtimeSigs);
        }
        return Collections.unmodifiableSet(signatures);
    }

    // Verify if a package is trusted
    public static boolean verifyTrustedPackage(PackageManager packageManager, String packageName) {
        Set<String> expectedSignatures = getExpectedSignaturesForPackage(packageName);
        if (expectedSignatures.isEmpty()) {
            MyLog.w("PackageHelper: no trusted signatures found for package " + packageName);
            return false;
        }

        try {
            PackageInfo packageInfo;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            } else {
                packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            }

            String actualSignature = getPackageSignature(packageInfo);
            if (actualSignature != null && expectedSignatures.contains(actualSignature)) {
                return true;
            } else {
                MyLog.w("PackageHelper: verification failed for package " + packageName + ", signature mismatch");
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            MyLog.w("PackageHelper: verification failed for package " + packageName + ", package not found");
            return false;
        }
    }

    // Get the SHA-256 signature hash of a package
    @Nullable
    private static String getPackageSignature(PackageInfo packageInfo) {
        try {
            Signature[] signatures;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                signatures = packageInfo.signingInfo.getApkContentsSigners();
            } else {
                signatures = packageInfo.signatures;
            }

            byte[] cert = signatures[0].toByteArray();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    // Check if a package is installed
    public static boolean isPackageInstalled(PackageManager packageManager, String packageName) {
        try {
            packageManager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // Save the map of package signatures to a file
    // Avoid calling this method from different processes simultaneously to ensure single-writer safety
    public static synchronized void saveTrustedSignaturesToFile(Context context, Map<String, Set<String>> signatures) {
        File tempFile = new File(context.getFilesDir(), "trusted_signatures_temp.json");
        File finalFile = new File(context.getFilesDir(), SIGNATURES_JSON_FILE);
        try (FileWriter writer = new FileWriter(tempFile)) {
            // Convert the map to JSON object where values are JSON arrays
            JSONObject jsonObject = new JSONObject();
            for (Map.Entry<String, Set<String>> entry : signatures.entrySet()) {
                jsonObject.put(entry.getKey(), new JSONArray(entry.getValue()));
            }
            writer.write(jsonObject.toString());
            // Rename temp file to final file atomically
            if (!tempFile.renameTo(finalFile)) {
                throw new IOException("Failed to rename temp file to final file.");
            }
        } catch (IOException | JSONException e) {
            MyLog.e("PackageHelper: failed to save trusted signatures: " + e);
        }
    }

    // Read the map of package signatures from a file, can be called from any process
    public static Map<String, Set<String>> readTrustedSignaturesFromFile(Context context) {
        File file = new File(context.getFilesDir(), SIGNATURES_JSON_FILE);
        Map<String, Set<String>> signatures = new HashMap<>();

        if (file.exists()) {
            try (FileReader reader = new FileReader(file);
                 BufferedReader bufferedReader = new BufferedReader(reader)) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    builder.append(line);
                }

                // Convert the JSON string back to a map
                JSONObject jsonObject = new JSONObject(builder.toString());
                Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String packageName = keys.next();
                    JSONArray signatureArray = jsonObject.getJSONArray(packageName);
                    Set<String> signatureSet = new HashSet<>();
                    for (int i = 0; i < signatureArray.length(); i++) {
                        signatureSet.add(signatureArray.getString(i));
                    }
                    signatures.put(packageName, signatureSet);
                }
            } catch (IOException | JSONException e) {
                MyLog.e("PackageHelper: failed to read trusted signatures: " + e);
            }
        }
        return signatures;
    }

    // Load runtime trusted signatures configuration
    // Make sure the map is immutable and the sets are unmodifiable
    public static void configureRuntimeTrustedSignatures(Map<String, Set<String>> signatures) {
        RUNTIME_TRUSTED_PACKAGES.clear();
        for (Map.Entry<String, Set<String>> entry : signatures.entrySet()) {
            RUNTIME_TRUSTED_PACKAGES.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new HashSet<>(entry.getValue()))
            );
        }
        MyLog.i("PackageHelper: loaded runtime signatures for " + signatures.size() + " packages");
    }
}
