package front;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

import controller.RankController;
import dto.RankDTO;

public class RankingView extends JFrame {
	private static final long serialVersionUID = 1L;
	private static RankingView inst = new RankingView();
	private static RankController rc = new RankController();
	private JTable table;
    private DefaultTableModel model;

	public static RankingView getInstance() {
		return inst;
	}
	
	private RankingView() {
		setTitle("Tower Break Ranking");
        setSize(800, 800);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setResizable(false);
        setLayout(null);
        setBackground(Color.WHITE);

        JLabel titleLb = new JLabel("RANKING", SwingConstants.CENTER);
        titleLb.setFont(new Font("굴림", Font.BOLD, 30));
        titleLb.setBounds(200, 30, 400, 50);
        add(titleLb);

        // 테이블 컬럼 설정
        String[] header = {"Ranking", "아이디", "점수", "Block"};
        model = new DefaultTableModel(header, 0) {
        	private static final long serialVersionUID = 1L;
        	@Override
        	public boolean isCellEditable(int row, int column) {
        		return false;
        	}
        };
        table = new JTable(model);
        table.setRowHeight(40);
        table.setFont(new Font("돋움", Font.PLAIN, 16));
        
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBounds(100, 100, 600, 500);
        add(scrollPane);

        JButton backBtn = new JButton("메뉴로 돌아가기");
        backBtn.setBounds(300, 650, 200, 50);
        backBtn.addActionListener(e -> {
            setVisible(false);
            MenuView.getInstance().start();
        });
        add(backBtn);		
		
		this.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				setVisible(false);
				MenuView.getInstance().start();				
			}
		});
	}
	
	public void start() {
        // 호출될 때마다 리스트를 새로 갱신
        model.setRowCount(0); 
        RankDTO[] list = rc.getRank();
        for(int i=0; i< list.length; i++) {
        	if(list[i] != null) { // 기록이 5개보다 적을 경우를 NTE 방지
        		String[] row = new String[4];
            	row[0] = String.format("%d", i+1);
            	row[1] = list[i].getName();
            	row[2] = String.format("%d", list[i].getScore());
            	row[3] = String.format("%d", list[i].getBlock());
                model.addRow(row);
        	}
        }
        
        setLocationRelativeTo(null);
        setVisible(true);
    }

}
