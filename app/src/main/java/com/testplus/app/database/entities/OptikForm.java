package com.testplus.app.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "optik_forms")
public class OptikForm {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String ad;
    public String kagit; // A4, A5, A6
    public String yon;   // DIKEY, YATAY
    public long olusturmaTarihi;

    public OptikForm() {
        olusturmaTarihi = System.currentTimeMillis();
    }
}
