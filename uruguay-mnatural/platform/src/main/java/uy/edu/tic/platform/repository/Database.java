package uy.edu.tic.platform.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class Database {
  private final JdbcTemplate jdbc;

  public Database(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> list(String sql, Object... args) {
    return jdbc.queryForList(sql, args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = list(sql, args);
    if (rows.isEmpty()) throw new NoSuchElementException("Recurso no encontrado");
    return rows.get(0);
  }

  public Optional<Map<String, Object>> optional(String sql, Object... args) {
    return list(sql, args).stream().findFirst();
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  public long insert(String sql, Object... args) {
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var p = c.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
          return p;
        },
        keys);
    return Objects.requireNonNull(keys.getKey()).longValue();
  }
}
