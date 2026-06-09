package controller;

import dto.RankDTO;
import service.RankService;

// 랭킹 정보에 대한 사용자 요청 처리
public class RankController {
	private RankService rs;
	
	public RankController() {
		rs = new RankService();
	}
	
	// 사용자가 랭킹 추가를 요청
	public void addRank(RankDTO rank) {
		rs.addRank(rank);
	}
	
	// 사용자가 TOP5 랭킹 조회를 요청
	public RankDTO[] getRank() {
		return rs.getRank();
	}
}