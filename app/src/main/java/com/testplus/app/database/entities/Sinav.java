package com.testplus.app.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sinavlar")
public class Sinav {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String ad;
    public long optikFormId;
    public String yanlisCezasi; // YOK, DORT_BIR, UC_BIR, IKI_BIR, BIR_BIR
    public long sinavTarihi;
    public long olusturmaTarihi;

    public Sinav() {
        olusturmaTarihi = System.currentTimeMillis();
    }
}
