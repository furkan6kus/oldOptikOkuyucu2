package com.testplus.app.database.dao;

import androidx.room.*;
import com.testplus.app.database.entities.CevapAnahtari;
import java.util.List;

@Dao
public interface CevapAnahtariDao {
    @Query("SELECT * FROM cevap_anahtarlari WHERE sinavId = :sinavId")
    List<CevapAnahtari> getBySinavId(long sinavId);

    @Query("SELECT * FROM cevap_anahtarlari WHERE sinavId = :sinavId AND optikFormAlanId = :alanId")
    CevapAnahtari getBySinavAndAlan(long sinavId, long alanId);

    @Insert
    long insert(CevapAnahtari cevapAnahtari);

    @Update
    void update(CevapAnahtari cevapAnahtari);

    @Query("DELETE FROM cevap_anahtarlari WHERE sinavId = :sinavId AND optikFormAlanId = :alanId")
    void deleteBySinavAndAlan(long sinavId, long alanId);

    @Query("DELETE FROM cevap_anahtarlari WHERE sinavId = :sinavId")
    void deleteBySinavId(long sinavId);
}
