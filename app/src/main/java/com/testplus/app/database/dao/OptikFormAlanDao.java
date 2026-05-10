package com.testplus.app.database.dao;

import androidx.room.*;
import com.testplus.app.database.entities.OptikFormAlan;
import java.util.List;

@Dao
public interface OptikFormAlanDao {
    @Query("SELECT * FROM optik_form_alanlar WHERE formId = :formId ORDER BY siraNo ASC")
    List<OptikFormAlan> getByFormId(long formId);

    @Query("SELECT * FROM optik_form_alanlar WHERE id = :id")
    OptikFormAlan getById(long id);

    @Insert
    long insert(OptikFormAlan alan);

    @Update
    void update(OptikFormAlan alan);

    @Delete
    void delete(OptikFormAlan alan);

    @Query("DELETE FROM optik_form_alanlar WHERE formId = :formId")
    void deleteByFormId(long formId);

    @Query("DELETE FROM optik_form_alanlar WHERE id = :id")
    void deleteById(long id);
}
