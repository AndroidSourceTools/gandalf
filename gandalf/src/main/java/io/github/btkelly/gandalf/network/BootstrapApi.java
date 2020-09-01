/**
 * Copyright 2016 Bryan Kelly
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * <p>
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.github.btkelly.gandalf.network;

import android.content.Context;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import io.github.btkelly.gandalf.models.Bootstrap;
import io.github.btkelly.gandalf.models.BootstrapException;
import io.github.btkelly.gandalf.models.BootstrapResponse;
import io.github.btkelly.gandalf.utils.HandlerExecutor;
import io.github.btkelly.gandalf.utils.StringUtils;
import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Network class for fetching the bootstrap file
 */
public class BootstrapApi {

    private static final int DEFAULT_CACHE_SIZE = 1024;
    private static final long DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30;

    @Nullable
    private final JsonDeserializer<Bootstrap> customDeserializer;
    private final String bootStrapUrl;
    private final OkHttpClient okHttpClient;
    private final Executor executor;

    /**
     * Creates a bootstrap api class
     *
     * @param context            - Android context used for setting up http cache directory
     * @param okHttpClient       - OkHttpClient to be used for requests, falls back to default if null
     * @param bootStrapUrl       - url to fetch the bootstrap file from
     * @param customDeserializer - a custom deserializer for parsing the JSON response
     */
    @SuppressWarnings("PMD.CloseResource")
    public BootstrapApi(
            Context context,
            @Nullable OkHttpClient okHttpClient,
            String bootStrapUrl,
            @Nullable JsonDeserializer<Bootstrap> customDeserializer,
            @Nullable Executor executor
    ) {
        this.bootStrapUrl = bootStrapUrl;
        this.customDeserializer = customDeserializer;

        if (okHttpClient == null) {
            File cacheDir = context.getCacheDir();
            Cache cache = new Cache(cacheDir, DEFAULT_CACHE_SIZE);

            this.okHttpClient = new OkHttpClient.Builder()
                    .cache(cache)
                    .connectTimeout(DEFAULT_CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(DEFAULT_CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();
        } else {
            this.okHttpClient = okHttpClient;
        }

        if (executor == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                this.executor = context.getMainExecutor();
            } else {
                this.executor = new HandlerExecutor(new Handler(context.getMainLooper()));
            }
        } else {
            this.executor = executor;
        }
    }

    /**
     * Will execute a request to fetch the application bootstrap file specified by the bootstrap
     * url set on the Gandalf class. Request is executed on a background thread.
     *
     * @param bootstrapCallback - a BootstrapCallback to receive call backs for the network call
     * @throws IllegalStateException - throws if the bootstrap url has not been set
     */
    public void fetchBootstrap(final BootstrapCallback bootstrapCallback) {

        if (StringUtils.isBlank(bootStrapUrl)) {
            throw new IllegalStateException("You must supply a bootstrap url");
        }

        Request request = new Request.Builder()
                .url(bootStrapUrl)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                postError(bootstrapCallback, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {

                GsonBuilder gsonBuilder = new GsonBuilder();

                if (customDeserializer != null) {
                    gsonBuilder.registerTypeAdapter(Bootstrap.class, customDeserializer);
                }

                Gson gson = gsonBuilder.create();

                try {
                    BootstrapResponse bootstrapResponse = gson.fromJson(response.body().string(), BootstrapResponse.class);
                    if (bootstrapResponse != null) {
                        final Bootstrap bootstrap = bootstrapResponse.getAndroid();
                        if (bootstrap != null) {
                            executor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    bootstrapCallback.onSuccess(bootstrap);
                                }
                            });
                        } else {
                            postError(bootstrapCallback, new BootstrapException("No \"android\" key found in the JSON response"));
                        }
                    } else {
                        postError(bootstrapCallback, new BootstrapException("No content found in the JSON response"));
                    }
                } catch (JsonSyntaxException e) {
                    postError(bootstrapCallback, e);
                }
            }
        });
    }

    private void postError(final BootstrapCallback bootstrapCallback, final Exception e) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                bootstrapCallback.onError(e);
            }
        });
    }

}
