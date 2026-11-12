package com.possaas.modules.restaurant.query;

import com.possaas.modules.restaurant.application.port.RestaurantNoteStore;
import com.possaas.modules.restaurant.dto.AdminRestaurantNoteResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcRestaurantNoteStore implements RestaurantNoteStore {
    private static final String SELECT = """
        select n.id, n.content, n.author_id, u.name as author_name, n.created_at
        from restaurant_admin_notes n left join users u on u.id = n.author_id
        where n.restaurant_id = :restaurantId and n.deleted_at is null
        """;
    private static final RowMapper<AdminRestaurantNoteResponse> NOTE = (rs, i) -> new AdminRestaurantNoteResponse(
        rs.getObject("id", UUID.class), rs.getString("content"), rs.getObject("author_id", UUID.class),
        rs.getString("author_name"), rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<AdminRestaurantNoteResponse> findActive(UUID restaurantId, int limit) {
        return jdbc.query(SELECT + " order by n.created_at desc, n.id desc limit :limit",
            new MapSqlParameterSource("restaurantId", restaurantId).addValue("limit", limit), NOTE);
    }

    @Override
    public Optional<AdminRestaurantNoteResponse> findActive(UUID restaurantId, UUID noteId) {
        return jdbc.query(SELECT + " and n.id = :id",
            new MapSqlParameterSource("restaurantId", restaurantId).addValue("id", noteId), NOTE).stream().findFirst();
    }

    @Override
    public void insert(UUID noteId, UUID restaurantId, UUID authorId, String content, Instant createdAt) {
        jdbc.update("""
            insert into restaurant_admin_notes(id, restaurant_id, author_id, content, created_at)
            values (:id, :restaurantId, :authorId, :content, :createdAt)
            """, new MapSqlParameterSource().addValue("id", noteId).addValue("restaurantId", restaurantId)
                .addValue("authorId", authorId).addValue("content", content).addValue("createdAt", Timestamp.from(createdAt)));
    }

    @Override
    public boolean softDelete(UUID restaurantId, UUID noteId, UUID actorId, Instant deletedAt) {
        return jdbc.update("""
            update restaurant_admin_notes set deleted_at = :deletedAt, deleted_by = :actor
            where id = :id and restaurant_id = :restaurantId and deleted_at is null
            """, new MapSqlParameterSource().addValue("deletedAt", Timestamp.from(deletedAt)).addValue("actor", actorId)
                .addValue("id", noteId).addValue("restaurantId", restaurantId)) == 1;
    }
}
