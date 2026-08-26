import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버는 5173, Spring 은 8080 이라 그대로 fetch 하면 CORS 에 막힌다.
// 서버에 CORS 설정을 넣는 대신 여기서 프록시한다. 서버가 개발 환경을 몰라도 되게.
//
// 빌드 결과는 Spring 의 정적 자원 폴더로 보낸다.
// 그러면 npm run build 후 gradlew bootRun 하나로 화면까지 뜬다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
