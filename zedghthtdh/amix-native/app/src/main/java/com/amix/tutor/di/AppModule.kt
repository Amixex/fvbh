package com.amix.tutor.di

import android.content.Context
import androidx.room.Room
import com.amix.tutor.data.AppDatabase
import com.amix.tutor.data.ChatDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "amix.db").build()

    @Provides
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()
}
