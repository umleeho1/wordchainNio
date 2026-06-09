package dto;

public class TowerDTO {
	private int combo;
	private int block;
	private int score;
	
	public TowerDTO(int combo, int block, int score) {
		this.combo = combo;
		this.block = block;
		this.score = score;
	}
	
	public int getCombo() {
		return combo;
	}
	public int getBlock() {
		return block;
	}
	public int getScore() {
		return score;
	}
}
