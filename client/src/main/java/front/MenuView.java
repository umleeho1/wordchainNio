package front;

import java.awt.Color;
import java.awt.Font;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import controller.MainController;
import controller.TowerController;

public class MenuView extends JFrame {
	private static final long serialVersionUID = 1L;
	private static MenuView inst = new MenuView();
	private JLabel titleLabel = new JLabel("WORD CHAIN");
	private JLabel idLabel = new JLabel("ID:");
	private JTextField idField = new JTextField();
	private JButton btnNormal = new JButton("1, 끝말잇기 게임");
	private JButton btnTower = new JButton("2. 타워 브레이크");
	private JButton btnRank = new JButton("3. 랭킹 보기");
	private int btnWidth = 250, btnHeight = 50, btnX = 75;

	public static MenuView getInstance() {
		return inst;
	}

	private MenuView() {
		btnNormal.setEnabled(false);
		btnTower.setEnabled(false);
		btnRank.setEnabled(false);

		setTitle("Word Chain - Main Menu");
		setSize(400, 600);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);// 우측상단 X박스
		setResizable(false);
		setLayout(null);// 절대좌표 사용을 위해 레이아웃 해제
		getContentPane().setBackground(Color.darkGray);
		// 제목 라벨
		titleLabel.setFont(new Font("Areal", Font.BOLD, 40));
		titleLabel.setForeground(Color.white);
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setBounds(50, 50, 300, 60);
		add(titleLabel);
		// 아이디 입력창
		idLabel.setForeground(Color.LIGHT_GRAY);
		idLabel.setBounds(70, 150, 30, 30);
		add(idLabel);
		idField.setBounds(110, 150, 200, 30);
		add(idField);
		// 메뉴 추가
		btnNormal.setBounds(btnX, 230, btnWidth, btnHeight);
		setButton(btnNormal);
		btnNormal.addActionListener(e -> {
			MainController.getInstance().openWordChain();
		});
		add(btnNormal);

		btnTower.setBounds(btnX, 300, btnWidth, btnHeight);
		setButton(btnTower);
		btnTower.addActionListener(e -> {
			MainController.getInstance().openTowerBreak();
		});
		add(btnTower);

		btnRank.setBounds(btnX, 370, btnWidth, btnHeight);
		setButton(btnRank);
		btnRank.addActionListener(e -> {
			MainController.getInstance().openRanking();
		});
		add(btnRank);

		idField.addActionListener(e -> {
			boolean ok = MainController.getInstance().submitId(idField.getText());
			if (ok) {
				btnNormal.setEnabled(true);
				btnTower.setEnabled(true);
				btnRank.setEnabled(true);
			}
		});
		
		new Thread(() -> {
			TowerController.getInstance().getWordList();
		}).start();
	}

	public void start() {
		idField.setText("");
		btnNormal.setEnabled(false);
		btnTower.setEnabled(false);
		btnRank.setEnabled(false);	// 다시 메뉴로 돌아왔을 때 버튼이 켜져있는거 방지
		setLocationRelativeTo(null); // 화면을 정 중앙으로 위치
		setVisible(true); // 메뉴 표시
	}

	public void setButton(JButton btn) {
		btn.setFont(new Font("맑은고딕", Font.BOLD, 16));
		btn.setBackground(Color.DARK_GRAY);
		btn.setForeground(Color.white);
		btn.setFocusPainted(false); // 테두리 제거
	}

}
