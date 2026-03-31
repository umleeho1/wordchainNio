package util;

import java.io.*;
import java.nio.ByteBuffer;

public class FastSerializer {

    private static final ThreadLocal<FastSerializer> INSTANCE = 
            ThreadLocal.withInitial(() -> new FastSerializer());

    private final ExposedBAOS bos = new ExposedBAOS(4096);
    private ReusableObjectOutputStream oos; // 커스텀 스트림 사용

    private FastSerializer() {
        try {
            // 최초 생성 시 bos와 연결
            this.oos = new ReusableObjectOutputStream(bos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static FastSerializer getInstance() {
        return INSTANCE.get();
    }

    public ByteBuffer serialize(Object obj) throws IOException {
        bos.reset(); // 버퍼 인덱스 초기화 (배열 재사용)
        
        // [수정 포인트] oos의 내부 상태를 초기화하고 헤더를 다시 쓰게 함
        oos.reset(); 
        oos.writeStreamHeader(); // 매 패킷마다 헤더를 포함시킴

        oos.writeObject(obj);
        oos.flush();

        int dataSize = bos.size();
        // ByteBuffer 할당 (송신 큐에 들어가야 하므로 이 객체 생성은 필요합니다)
        ByteBuffer buffer = ByteBuffer.allocate(4 + dataSize);
        buffer.putInt(dataSize);
        buffer.put(bos.getInternalBuffer(), 0, dataSize);
        buffer.flip();
        
        return buffer;
    }

    /**
     * 헤더를 강제로 다시 쓸 수 있게 확장한 클래스
     */
    private static class ReusableObjectOutputStream extends ObjectOutputStream {
        public ReusableObjectOutputStream(OutputStream out) throws IOException {
            super(out);
        }

        @Override
        public void writeStreamHeader() throws IOException {
            // 부모의 메서드를 호출하여 헤더(AC ED 00 05)를 씀
            super.writeStreamHeader();
        }
    }

    private static class ExposedBAOS extends ByteArrayOutputStream {
        public ExposedBAOS(int size) { super(size); }
        public byte[] getInternalBuffer() { return buf; }
    }
}