package com.testplus.app.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "optik_form_alanlar")
public class OptikFormAlan {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long formId;
    public String tur;    // CEVAPLAR, KITAPCIK, AD_SOYAD, SINIF, NUMARA
    public String yon;    // YATAY, DIKEY
    public String etiket;
    public String desen;  // ABCD, 0123456789, vs.
    public float solBosluk;
    public float ustBosluk;
    public int blokSayisi;
    public int bloktakiVeriSayisi;
    public String ders;
    public int ilkSoruNumarasi;
    public boolean blokArasiBosluk;
    public float posX;
    public float posY;
    public int siraNo;
}
