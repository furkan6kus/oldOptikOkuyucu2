package com.testplus.app.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.testplus.app.database.entities.Sinav;
import java.util.List;

@Dao
public interface SinavDao {
    @Query("SELECT * FROM sinavlar ORDER BY olusturmaTarihi DESC")
    LiveData<List<Sinav>> getAll();

    @Query("SELECT * FROM sinavlar WHERE id = :id")
    Sinav getById(long id);

    @Query("SELECT * FROM sinavlar WHERE optikFormId = :optikFormId ORDER BY olusturmaTarihi DESC LIMIT 1")
    Sinav getLatestByOptikFormId(long optikFormId);

    @Insert
    long insert(Sinav sinav);

    @Update
    void update(Sinav sinav);

    @Delete
    void delete(Sinav sinav);

    @Query("DELETE FROM sinavlar WHERE id = :id")
    void deleteById(long id);
}
