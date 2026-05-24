/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "#0a0a0f",
        foreground: "#e8e8f0",
        primary: { DEFAULT: "#7c5cbf" },
        accent: "#a78bfa",
        muted:  "#2d2d3f",
        destructive: "#ef4444"
}
    }
    ,
  },
  plugins: [],
}

