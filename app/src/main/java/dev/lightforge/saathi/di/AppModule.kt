package dev.lightforge.saathi.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lightforge.saathi.BuildConfig
import dev.lightforge.saathi.auth.TokenManager
import dev.lightforge.saathi.data.SaathiDatabase
import dev.lightforge.saathi.data.dao.MenuItemDao
import dev.lightforge.saathi.data.dao.ReservationDao
import dev.lightforge.saathi.data.dao.RestaurantConfigDao
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.AuthInterceptor
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt DI module providing application-scoped dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // --- Networking ---

    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            // TODO: Add production certificate pins for api.saathi.help
            // .add("api.saathi.help", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        certificatePinner: CertificatePinner
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .certificatePinner(certificatePinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("${BuildConfig.AEGIS_API_BASE_URL}/api/v1/saathi/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideAegisApiClient(retrofit: Retrofit): AegisApiClient {
        return retrofit.create(AegisApiClient::class.java)
    }

    // --- Database ---

    @Provides
    @Singleton
    fun provideSaathiDatabase(@ApplicationContext context: Context): SaathiDatabase {
        return Room.databaseBuilder(
            context,
            SaathiDatabase::class.java,
            "saathi_db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMenuItemDao(db: SaathiDatabase): MenuItemDao = db.menuItemDao()

    @Provides
    fun provideReservationDao(db: SaathiDatabase): ReservationDao = db.reservationDao()

    @Provides
    fun provideRestaurantConfigDao(db: SaathiDatabase): RestaurantConfigDao = db.restaurantConfigDao()
}
