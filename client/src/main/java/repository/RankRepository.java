package repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import dto.RankDTO;

// 랭킹 정보 데이터 CRUD
public class RankRepository {
	private static RankRepository rr = null;
	private RankDTO[] rlist = new RankDTO[5]; // 순위 5위까지만 출력할 것이기 때문에 배열로 선언
	private final static String url = "jdbc:postgresql://192.168.0.63:5432/postgres";
	private final static String user = "postgres";
	private final static String password = "1234";
	
	private RankRepository() {};
	
	// 싱글톤 패턴 구현
	public static RankRepository getInstance() {
		if(rr == null) {
			rr = new RankRepository();
		}
		return rr;
	}
	
	// 상위 5개의 랭킹 정보만 DB에서 select
	public RankDTO[] getTop5() {
		try {
			Connection conn = DriverManager.getConnection(url, user, password);
			String sql = "select * from ranking order by score desc limit 5 offset 0";
			Statement smt = conn.createStatement();
			ResultSet rs = smt.executeQuery(sql);
			
			int idx = 0;
			while(rs.next()) {
				String name = rs.getString("name");
				int score = rs.getInt("score");
				int block = rs.getInt("block");
				RankDTO rank = new RankDTO(name, score, block);
				rlist[idx] = rank;
				idx++;
			}
			
			rs.close();
			smt.close();
			conn.close();
			
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return rlist;
	}
	
	// 새로운 랭킹 정보를 DB에 insert
	public void add(RankDTO rank) {
		if(rank.getScore() > 0) {
			try {
				Connection conn = DriverManager.getConnection(url, user, password);
				String sql = "insert into ranking(name, score, block) values(?, ?, ?)";
				PreparedStatement psmt = conn.prepareCall(sql);
				psmt.setString(1, rank.getName());
				psmt.setInt(2, rank.getScore());
				psmt.setInt(3, rank.getBlock());
				psmt.executeUpdate();
				
				psmt.close();
				conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}
	
}