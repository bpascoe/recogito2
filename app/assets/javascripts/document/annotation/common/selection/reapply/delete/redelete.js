define([
  'common/config',
  'common/utils/annotationUtils',
  'document/annotation/common/selection/reapply/delete/modal'
], function(Config, AnnotationUtils, Modal) {

  var ReDelete = function(annotations) {

    var actionHandlers = {},

        isAllowed = function(annotation) {
          if (Config.isAdmin)
            return true;

          var commentsByOthers =
                 AnnotationUtils.getBodiesOfType(annotation, 'COMMENT')
                   .filter(function(b) {
                     return b.last_modified_by != Config.me;
                 });

          return commentsByOthers.length == 0;
        },

        onDelete = function(toDelete) {
          var allowed = toDelete.filter(isAllowed);
          actionHandlers.delete(allowed);
        },

        executeAdvanced = function(annotation, args) {
          var quote = AnnotationUtils.getQuote(annotation),
              status = args.applyIfStatus,

              filtered = annotations.filterByQuote(quote),

              toDelete = (status) ?
                filtered.filter(function(a) {
                  var statusValues = AnnotationUtils.getStatus(a);
                  // Note: we're only evaluating the first status
                  return (statusValues.length > 0) && statusValues[0] == status;
                }) : filtered;

          onDelete(toDelete);
        },

        onGoAdvanced = function(annotation) {
          var bulkEl = document.getElementById('bulk-annotation'),
              evt = new Event('open'),

              okListener = function(evt) {
                executeAdvanced(annotation, evt.args);
                bulkEl.removeEventListener('ok', okListener, false);
              }

          evt.args = {
            mode: 'DELETE',
            annotations: annotations.listAnnotations(),
            original: annotation
          };

          bulkEl.dispatchEvent(evt);
          bulkEl.addEventListener('ok', okListener);
        },

        reapplyDelete = function(annotation) {
          var quote = AnnotationUtils.getQuote(annotation),
              annotated = annotations.filterByQuote(quote).filter(function(a) {
                // Don't doublecount the current annotation
                return a.annotation_id != annotation.annotation_id;
              });

          if (annotated.length > 0)
            Modal.prompt(quote, annotated,
              onDelete.bind(this, annotated),
              onGoAdvanced.bind(this, annotation));
        },

        on = function(evt, handler) {
          actionHandlers[evt] = handler;
        };

    this.reapplyDelete = reapplyDelete;
    this.on = on;
  };

  return ReDelete;

});
