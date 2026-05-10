package com.testplus.app.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.testplus.app.database.entities.OgrenciKagidi;
import java.util.List;

@Dao
public interface OgrenciKagidiDao {
    @Query("SELECT * FROM ogrenci_kagitlari WHERE sinavId = :sinavId ORDER BY tarih DESC")
    LiveData<List<OgrenciKagidi>> getBySinavId(long sinavId);

    @Query("SELECT * FROM ogrenci_kagitlari WHERE id = :id")
    OgrenciKagidi getById(long id);

    @Insert
    long insert(OgrenciKagidi kagit);

    @Update
    void update(OgrenciKagidi kagit);

    @Delete
    void delete(OgrenciKagidi kagit);

    @Query("DELETE FROM ogrenci_kagitlari WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM ogrenci_kagitlari WHERE sinavId = :sinavId")
    void deleteBySinavId(long sinavId);
}
