package com.testplus.app.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "ogrenci_kagitlari")
public class OgrenciKagidi {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long sinavId;
    public String ad;
    public String numara;
    public String sinif;
    /** Optik KITAPCIK alanından okunan veya manuel girilen kitapçık (tek harf veya birleşik). */
    public String kitapcik;
    public String cevaplarJson; // JSON: {"Türkçe": ["A","B",...], "Matematik": [...]}
    public String sonuclarJson; // JSON: {"Türkçe": {"dogru":18,"yanlis":1,"bos":1,"net":17.67}}
    public long tarih;

    public OgrenciKagidi() {
        tarih = System.currentTimeMillis();
    }
}
