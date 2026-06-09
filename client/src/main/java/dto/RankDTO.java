package dto;

public class RankDTO {
	private String name;
	private int score;
	private int block;
	
	public RankDTO(String name, int score, int block) {
		this.name = name;
		this.score = score;
		this.block = block;
	}
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public int getScore() {
		return score;
	}
	public void setScore(int score) {
		this.score = score;
	}
	public int getBlock() {
		return block;
	}
	public void setBlock(int block) {
		this.block = block;
	}
}