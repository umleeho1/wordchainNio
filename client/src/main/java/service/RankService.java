package service;

import dto.RankDTO;
import repository.RankRepository;

// 랭킹 관리를 수행
// 기능 : 랭킹 등록, 랭킹 조회(1~5위까지)
public class RankService {
	private RankRepository rr = RankRepository.getInstance();

	// 새로운 랭킹 등록을 수행
	public void addRank(RankDTO rank) {
		rr.add(rank);
	}
	
	// 1~5위까지의 랭킹 조회를 수행
	public RankDTO[] getRank() {
		return rr.getTop5();
	}
}