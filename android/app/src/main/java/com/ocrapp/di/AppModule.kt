package com.ocrapp.di

import android.content.Context
import androidx.room.Room
import com.ocrapp.BuildConfig
import com.ocrapp.data.OcrDatabase
import com.ocrapp.data.ScanDao
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.RunPodApi
import com.ocrapp.ocr.RunPodCredentialsProvider
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
        Room.databaseBuilder(context, OcrDatabase::class.java, OcrDatabase.NAME).build()

    @Provides
    fun provideScanDao(database: OcrDatabase): ScanDao = database.scanDao()

    @Provides
    @Singleton
    fun provideCredentialsProvider(settings: SettingsStore): RunPodCredentialsProvider = settings

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Generous read timeout: a single request carries every page of a document, and
        // a cold RunPod worker has to load the model before it answers at all.
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
    fun provideRunPodApi(client: OkHttpClient, json: Json): RunPodApi = Retrofit.Builder()
        .baseUrl(RUNPOD_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(RunPodApi::class.java)

    private const val RUNPOD_BASE_URL = "https://api.runpod.ai/"
}
