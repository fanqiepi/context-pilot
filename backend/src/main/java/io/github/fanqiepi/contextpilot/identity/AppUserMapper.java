package io.github.fanqiepi.contextpilot.identity;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppUserMapper {

    String SELECT_COLUMNS = "id, user_key, display_name, status, created_at, updated_at, deleted";

    @Select("SELECT " + SELECT_COLUMNS
            + " FROM app_user WHERE user_key = #{userKey} AND status = 'ACTIVE' AND deleted = 0")
    AppUserEntity selectActiveByKey(String userKey);

    @Select("SELECT " + SELECT_COLUMNS
            + " FROM app_user WHERE id = #{id} AND status = 'ACTIVE' AND deleted = 0 FOR UPDATE")
    AppUserEntity lockActiveById(UUID id);
}
