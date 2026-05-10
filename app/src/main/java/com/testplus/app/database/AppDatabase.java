package com.testplus.app.database;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.testplus.app.database.dao.*;
import com.testplus.app.database.entities.*;

@Database(
    entities = {OptikForm.class, OptikFormAlan.class, Sinav.class, CevapAnahtari.class, OgrenciKagidi.class},
    version = 2,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE ogrenci_kagitlari ADD COLUMN kitapcik TEXT");
        }
    };

    public abstract OptikFormDao optikFormDao();
    public abstract OptikFormAlanDao optikFormAlanDao();
    public abstract SinavDao sinavDao();
    public abstract CevapAnahtariDao cevapAnahtariDao();
    public abstract OgrenciKagidiDao ogrenciKagidiDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "testplus_db"
                    ).addMigrations(MIGRATION_1_2).build();
                }
            }
        }
        return INSTANCE;
    }
}
