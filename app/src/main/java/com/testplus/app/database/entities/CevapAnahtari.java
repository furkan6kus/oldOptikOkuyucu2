package com.testplus.app.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "cevap_anahtarlari")
public class CevapAnahtari {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long sinavId;
    public long optikFormAlanId;
    public String ders;
    public String cevaplarJson; // JSON array of answers e.g. ["A","B","C","D"]
}
