import { useState } from "react";
import { createRoot } from "react-dom/client";
import { usePostTranslation } from "../src/components/use-post-translation";
import { useCommunityNoteTranslation } from "../src/components/use-community-note-translation";

function Translations() {
  const post = usePostTranslation({
    accountId: "fixture",
    postId: "123",
    text: "Original post",
    language: "en",
    active: true,
  });
  const note = useCommunityNoteTranslation(
    { noteId: "456", title: "Note", text: "Original note", language: "en", footer: null },
    "fixture",
    true,
  );
  return (
    <>
      <div data-post data-loading={post.loading}>
        {post.visibleText}
      </div>
      <div data-note data-loading={note.loading}>
        {note.visibleNote.text}
      </div>
    </>
  );
}
function Fixture() {
  const [open, setOpen] = useState(true);
  return (
    <>
      <button type="button" data-toggle onClick={() => setOpen((value) => !value)}>
        Toggle
      </button>
      {open && <Translations />}
    </>
  );
}
const container = document.getElementById("root");
if (container !== null) createRoot(container).render(<Fixture />);
