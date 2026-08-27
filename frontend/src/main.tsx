import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./app";
import "./styles.css";
import "./media-content.css";
import "./supplemental.css";

const root = document.getElementById("root");
if (root === null) {
  throw new Error("NyTweetDeck root element was not found.");
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
