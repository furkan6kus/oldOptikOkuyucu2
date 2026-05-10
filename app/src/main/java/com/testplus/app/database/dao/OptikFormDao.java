package com.testplus.app.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.testplus.app.database.entities.OptikForm;
import java.util.List;

@Dao
public interface OptikFormDao {
    @Query("SELECT * FROM optik_forms ORDER BY olusturmaTarihi DESC")
    LiveData<List<OptikForm>> getAll();

    @Query("SELECT * FROM optik_forms WHERE id = :id")
    OptikForm getById(long id);

    @Insert
    long insert(OptikForm form);

    @Update
    void update(OptikForm form);

    @Delete
    void delete(OptikForm form);

    @Query("DELETE FROM optik_forms WHERE id = :id")
    void deleteById(long id);
}
