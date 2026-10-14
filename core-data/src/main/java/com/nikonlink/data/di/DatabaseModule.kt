package com.nikonlink.data.di

import android.content.Context
import androidx.room.Room
import com.nikonlink.data.db.NikonLinkDatabase
import com.nikonlink.data.db.dao.CameraDeviceDao
import com.nikonlink.data.db.dao.TransferTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NikonLinkDatabase =
        Room.databaseBuilder(
            context,
            NikonLinkDatabase::class.java,
            "nikonlink.db"
        ).build()

    @Provides
    fun provideCameraDeviceDao(database: NikonLinkDatabase): CameraDeviceDao =
        database.cameraDeviceDao()

    @Provides
    fun provideTransferTaskDao(database: NikonLinkDatabase): TransferTaskDao =
        database.transferTaskDao()
}
