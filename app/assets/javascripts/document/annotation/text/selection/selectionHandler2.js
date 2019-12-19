define([
  'common/config',
  'common/utils/annotationUtils',
  'document/annotation/common/selection/abstractSelectionHandler'],

  function(Config, Utils, AbstractSelectionHandler) {

  var SelectionHandler = function(rootNode, highlighter) {

    var self = this,

        isEnabled = true,

        currentSelection = false,

        /** Helper that clears the visible selection by 'unwrapping' the created span elements **/
        clearSelection = function() {
          currentSelection = false;
          jQuery.each(jQuery('.selection'), function(idx, el) {
            jQuery(el).contents().unwrap();
          });
          rootNode.normalize();
        },

        /** cf. http://stackoverflow.com/questions/3169786/clear-text-selection-with-javascript **/
        clearNativeSelection = function() {
          if (window.getSelection) {
            if (window.getSelection().empty)
              window.getSelection().empty();
            else if (window.getSelection().removeAllRanges)
              window.getSelection().removeAllRanges();
          } else if (document.selection) {
            document.selection.empty();
          }
        },

        getSelection = function() {
          return currentSelection;
        },

        setSelection = function(selection) {
          currentSelection = selection;
          // if (selection)
            // self.fireEvent('select', currentSelection);
        },

        onMouseup = function(e) {
          if (!isEnabled) return;

              // Click happend on an existing annotation span?
          var annotationSpan = jQuery(e.target).closest('.annotation'),

              // Or click was part of a new text selection?
              selection = rangy.getSelection(),

              // Check if selection is (fully or partially) on the editor
              isSelectionOnEditor =
                jQuery(selection.anchorNode) // mouse event target
                  .add(jQuery(selection.focusNode)) // start node of the selection
                  .closest('.editor-popup').length > 0,

              // Util function to check if the selection is an exact overlap to any
              // annotations that already exist at this point
              getExactOverlaps = function(newAnnotation, selectedSpans) {
                // All existing annotations at this point
                var existingAnnotations = [];

                selectedSpans.forEach(function(span) {
                  var enclosingAnnotationSpans = jQuery(span).closest('.annotation');
                      enclosingAnnotation = (enclosingAnnotationSpans.length > 0) ?
                        enclosingAnnotationSpans[0].annotation : false;

                  if (enclosingAnnotation && existingAnnotations.indexOf(enclosingAnnotation) === -1)
                    existingAnnotations.push(enclosingAnnotation);
                });

                if (existingAnnotations.length > 0)
                  return existingAnnotations.filter(function(anno) {
                    var isSameAnchor = anno.anchor == newAnnotation.anchor,
                        isSameQuote = Utils.getQuote(anno) == Utils.getQuote(newAnnotation);
                    return isSameAnchor && isSameQuote;
                  });
                else
                  return [];
              },

              selectedRange, annotation, exactOverlaps, bounds, spans;

          // If the mouseup happened on the editor, we'll ignore
          if (isSelectionOnEditor)
            return;

          // Not a selection, but a click outside editor/annotation - deselect
          if (selection.isCollapsed && annotationSpan.length === 0) {
            clearSelection();
            // self.fireEvent('select');
            return;
          }

          // Check for new text selection first - takes precedence over clicked
          // annotation span, otherwise we can't create annotations inside exsiting ones
          if (Config.writeAccess &&
              !selection.isCollapsed &&
               selection.rangeCount == 1 &&
               selection.getRangeAt(0).toString().trim().length > 0) {

             selectedRange = self.trimRange(selection.getRangeAt(0));
             annotation = self.rangeToAnnotationStub(selectedRange);
             bounds = selectedRange.nativeRange.getBoundingClientRect();

             spans = highlighter.wrapRange(selectedRange);
             jQuery.each(spans, function(idx, span) { span.className = 'selection'; });
             clearNativeSelection();

             exactOverlaps = getExactOverlaps(annotation, spans);

             if (exactOverlaps.length > 0) {
               // User selected over existing - reuse top-most original to avoid stratification
               clearSelection();
               currentSelection = {
                 isNew      : false,
                 annotation : exactOverlaps[0],
                 bounds     : bounds
               };
             } else {
               currentSelection = {
                 isNew      : true,
                 annotation : annotation,
                 bounds     : bounds,

                 // Text-UI specific field - speeds things up a bit
                 // in highlighter.convertSelectionToAnnotation
                 spans      : spans
               };
             }

             // self.fireEvent('select', currentSelection);
          } else if (annotationSpan.length > 0) {
            // Top-most annotation at this span
            annotation = highlighter.getAnnotationsAt(annotationSpan[0])[0];

            // A selection on an existing annotation
            currentSelection = {
              isNew      : false,
              annotation : annotation,
              bounds     : annotationSpan[0].getBoundingClientRect()
            };

            // self.fireEvent('select', currentSelection);
          }

          return false;
        },

        onKeyDown = function(e) {
          var key = e.which,

              stepSelection = function(selection) {
                if (selection) {
                  currentSelection = selection;
                  // self.fireEvent('select', currentSelection);
                }
              };

          if (currentSelection)
            if (key === 37)
              // Left arrow key
              stepSelection(highlighter.getAnnotationBefore(currentSelection.annotation));
            else if (key === 39)
              // Right arrow key
              stepSelection(highlighter.getAnnotationAfter(currentSelection.annotation));
        },

        setEnabled = function(enabled) {
          isEnabled = enabled;
        };

    jQuery(rootNode).mouseup(onMouseup);
    jQuery(document.body).keydown(onKeyDown);

    this.rootNode = rootNode;
    this.clearSelection = clearSelection;
    this.getSelection = getSelection;
    this.setSelection = setSelection;
    this.setEnabled = setEnabled;

    AbstractSelectionHandler.apply(this);
  };
  SelectionHandler.prototype = Object.create(AbstractSelectionHandler.prototype);

  /** We make this method overide-able for the sake of the TEI implementation **/
  SelectionHandler.prototype.rangeToAnnotationStub = function(selectedRange) {
    var rangeBefore = rangy.createRange();
    // A helper range from the start of the contentNode to the start of the selection
    rangeBefore.setStart(this.rootNode, 0);
    rangeBefore.setEnd(selectedRange.startContainer, selectedRange.startOffset);

    return {
      annotates: {
        document_id: Config.documentId,
        filepart_id: Config.partId,
        content_type: Config.contentType
      },
      anchor: 'char-offset:' + rangeBefore.toString().length,
      bodies: [
        { type: 'QUOTE', value: selectedRange.toString() }
      ]
    };
  };

  /** We make this method overide-able for the sake of the TEI implementation **/
  SelectionHandler.prototype.trimRange = function(range) {
    var quote = range.toString(),
        leadingSpaces = 0,
        trailingSpaces = 0;

    // Strip & count leading whitespace, adjust range
    while (quote.substring(0, 1) === ' ') {
      leadingSpaces += 1;
      quote = quote.substring(1);
    }

    if (leadingSpaces > 0)
      range.setStart(range.startContainer, range.startOffset + leadingSpaces);

    // Strip & count trailing whitespace, adjust range
    while (quote.substring(quote.length - 1) === ' ') {
      trailingSpaces += 1;
      quote = quote.substring(0, quote.length - 1);
    }

    if (trailingSpaces > 0)
      range.setEnd(range.endContainer, range.endOffset - trailingSpaces);

    return range;
  };

  return SelectionHandler;

});
