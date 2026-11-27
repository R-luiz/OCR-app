package com.ocrapp.di

import android.content.Context
import androidx.room.Room
import com.ocrapp.BuildConfig
import com.ocrapp.auth.OcrAuthApi
import com.ocrapp.auth.SignInStore
import com.ocrapp.data.OcrDatabase
import com.ocrapp.data.ScanDao
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.OcrBackends
import com.ocrapp.ocr.OcrConnectionProvider
import com.ocrapp.ocr.OcrHosts
import com.ocrapp.ocr.RunPodApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OcrDatabase =
        Room.databaseBuilder(context, OcrDatabase::class.java, OcrDatabase.NAME)
            .addMigrations(OcrDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideScanDao(database: OcrDatabase): ScanDao = database.scanDao()

    @Provides
    @Singleton
    fun provideConnectionProvider(settings: SettingsStore): OcrConnectionProvider = settings

    @Provides
    @Singleton
    fun provideSignInStore(settings: SettingsStore): SignInStore = settings

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Resubmitting a POST on a connection error can create a second billable job.
        // Status polling handles transient errors explicitly.
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        // BASIC only: BODY would dump base64 page images into logcat.
                        level = HttpLoggingInterceptor.Level.BASIC
                    },
                )
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideOcrBackends(client: OkHttpClient, json: Json): OcrBackends = OcrBackends(
        runPod = retrofit(OcrHosts.RUNPOD_BASE_URL, client, json).create(RunPodApi::class.java),
        managed = retrofit(OcrHosts.MANAGED_BASE_URL, client.withoutRedirects(), json)
            .create(RunPodApi::class.java),
    )

    @Provides
    @Singleton
    fun provideOcrAuthApi(client: OkHttpClient, json: Json): OcrAuthApi =
        retrofit(
            OcrHosts.MANAGED_BASE_URL,
            client.withoutRedirects().newBuilder().callTimeout(AUTH_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
            json,
        ).create(OcrAuthApi::class.java)

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private const val AUTH_CALL_TIMEOUT_SECONDS = 30L
}

/**
 * The managed service answers where it is addressed. A redirect would carry the pages
 * of a document to a host nobody chose, so it is an error rather than a detour.
 */
internal fun OkHttpClient.withoutRedirects(): OkHttpClient = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
