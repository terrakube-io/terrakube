export default {
  testEnvironment: "jsdom",
  transform: {
    "^.+\\.ts?$": ["ts-jest", { tsconfig: "tsconfig.test.json" }],
    "^.+\\.tsx?$": ["ts-jest", { tsconfig: "tsconfig.test.json" }],
    "^.+\\.jsx?$": ["ts-jest", { tsconfig: "tsconfig.test.json" }],
  },
  transformIgnorePatterns: [
    "/node_modules/(?!(@ant-design|@ungap/structured-clone|html-react-parser|html-dom-parser|domhandler|domutils|dom-serializer|domelementtype|entities|react-markdown|remark-.*|rehype-.*|unified|unist-.*|mdast-.*|hast-.*|micromark.*|vfile.*|bail|trough|devlop|ccount|zwitch|longest-streak|markdown-table|trim-lines|stringify-entities|property-information|html-url-attributes|space-separated-tokens|comma-separated-tokens|decode-named-character-reference|character-entities.*|character-reference-invalid|parse-entities|escape-string-regexp|is-plain-obj|is-alphabetical|is-alphanumerical|is-decimal|is-hexadecimal|estree-util-is-identifier-name)/)",
  ],
  moduleNameMapper: {
    "\\.(css|less|sass|scss)$": "identity-obj-proxy",
    "^.+\\.svg$": "jest-transformer-svg",
    "\\.(png|jpe?g|gif|webp)$": "<rootDir>/jest.fileMock.js",
    "\\?worker$": "<rootDir>/jest.fileMock.js",
    "^monaco-editor$": "<rootDir>/jest.fileMock.js",
    "^@/(.*)$": "<rootDir>/src/$1",
  },
  setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
};
