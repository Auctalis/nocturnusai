import { useRef, useEffect } from 'react'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { json } from '@codemirror/lang-json'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { syntaxHighlighting, HighlightStyle } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { searchKeymap } from '@codemirror/search'
import { autocompletion } from '@codemirror/autocomplete'

const highlightStyle = HighlightStyle.define([
  { tag: tags.string, color: '#007D79' },
  { tag: tags.number, color: '#24A148' },
  { tag: tags.bool, color: '#8A3FFC' },
  { tag: tags.null, color: '#878D96' },
  { tag: tags.propertyName, color: '#0F62FE' },
  { tag: tags.punctuation, color: '#697077' },
])

const lightTheme = EditorView.theme({
  '&': {
    backgroundColor: '#F2F4F8',
    color: '#121619',
    fontSize: '13px',
  },
  '.cm-content': {
    fontFamily: "'IBM Plex Mono', ui-monospace, Consolas, monospace",
    padding: '12px 0',
    caretColor: '#0F62FE',
  },
  '.cm-line': {
    padding: '0 16px',
  },
  '&.cm-focused .cm-cursor': {
    borderLeftColor: '#0F62FE',
  },
  '&.cm-focused .cm-selectionBackground, ::selection': {
    backgroundColor: '#D0E2FF',
  },
  '.cm-selectionBackground': {
    backgroundColor: '#D0E2FF !important',
  },
  '.cm-activeLine': {
    backgroundColor: 'rgba(15, 98, 254, 0.04)',
  },
  '.cm-gutters': {
    backgroundColor: '#F2F4F8',
    color: '#A2A9B0',
    border: 'none',
    borderRight: '1px solid #DDE1E6',
  },
  '.cm-activeLineGutter': {
    backgroundColor: 'rgba(15, 98, 254, 0.04)',
    color: '#4D5358',
  },
})

interface CodeEditorProps {
  value: string
  onChange: (value: string) => void
  onRun: () => void
}

export default function CodeEditor({ value, onChange, onRun }: CodeEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const onChangeRef = useRef(onChange)
  const onRunRef = useRef(onRun)
  onChangeRef.current = onChange
  onRunRef.current = onRun

  useEffect(() => {
    if (!containerRef.current) return

    const runKeymap = keymap.of([
      {
        key: 'Mod-Enter',
        run: () => {
          onRunRef.current()
          return true
        },
      },
    ])

    const updateListener = EditorView.updateListener.of((update) => {
      if (update.docChanged) {
        onChangeRef.current(update.state.doc.toString())
      }
    })

    const state = EditorState.create({
      doc: value,
      extensions: [
        runKeymap,
        keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap]),
        history(),
        json(),
        syntaxHighlighting(highlightStyle),
        lightTheme,
        autocompletion(),
        updateListener,
        EditorView.lineWrapping,
      ],
    })

    const view = new EditorView({
      state,
      parent: containerRef.current,
    })

    viewRef.current = view
    return () => view.destroy()
    // only create editor once
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Sync external value changes
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const current = view.state.doc.toString()
    if (current !== value) {
      view.dispatch({
        changes: { from: 0, to: current.length, insert: value },
      })
    }
  }, [value])

  return (
    <div className="editor-wrapper" style={{ flex: 1, minHeight: 200 }}>
      <div ref={containerRef} style={{ height: '100%' }} />
    </div>
  )
}
