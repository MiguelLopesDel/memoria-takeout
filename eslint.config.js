import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'target', 'server'] },
  {
    files: ['src/**/*.{ts,tsx}', 'vite.config.ts'],
    extends: [...tseslint.configs.recommended, reactHooks.configs.flat.recommended],
    rules: {
      // The codebase talks to a schemaless /api; `any` at the fetch boundary is accepted.
      '@typescript-eslint/no-explicit-any': 'off',
      // The whole app fetches data via useEffect (pre-TanStack-Query pattern); this rule
      // condemns that wholesale. Revisit if data fetching moves to a query library.
      'react-hooks/set-state-in-effect': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      eqeqeq: ['error', 'smart'],
      'no-var': 'error',
      'prefer-const': 'error',
      // Size limits (video's "quality gate" idea): warnings act as a ratchet — do not raise them.
      'max-lines': ['warn', { max: 2500, skipBlankLines: true, skipComments: true }],
      'max-lines-per-function': ['warn', { max: 400, skipBlankLines: true, skipComments: true }],
    },
  }
);
