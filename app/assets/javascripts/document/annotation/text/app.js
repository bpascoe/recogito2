require.config({
  baseUrl: "/assets/javascripts/",
  fileExclusionRegExp: /^lib$/,
  paths: {
    marked: '/webjars/marked/0.3.6/marked.min',
    i18n: '../vendor/i18n'
  }
});

require([
  'document/annotation/common/baseTextApp',
  'document/annotation/text/selection/highlighter',
  'document/annotation/text/selection/selectionHandler',
  'document/annotation/text/selection/phraseAnnotator'
], function(BaseTextApp, Highlighter, SelectionHandler, PhraseAnnotator) {

  jQuery(document).ready(function() {
    var contentNode = document.getElementById('content'),
        highlighter = new Highlighter(contentNode),
        selector = new SelectionHandler(contentNode, highlighter),
        phraseAnnotator = new PhraseAnnotator(contentNode, highlighter);

    new BaseTextApp(contentNode, highlighter, selector, phraseAnnotator);
  });
var folderId = sessionStorage.getItem("folderId");
if (folderId)
  $.get( document.location.origin + "/api/directory/my/" + folderId, function(data) {
    var items = data.items;
    if (items.length > 1)
      $.each(items,function(index,item){
        var docId =item.id;
        if (docId != document.location.pathname.split('/')[2]) 
          $.get( document.location.origin + "/document/"+docId+"/part/1/edit", function(data) {
            var lis = $($(data).find('ul.menu')[1]).find('li')
            lis.removeClass('active');
            lis.find('a').removeAttr('onclick');
            $('.sidebar ul.menu').append(lis);
          });
          // var lis = $('.sidebar ul.menu').find('li.active')
          // lis.removeAttr('class');
          // lis.find('a').removeAttr('onclick');
          // lis.find('a').attr('href') = document.location.origin + "/document/"+docId+"/part/1/edit";
          // $('.sidebar ul.menu').append(lis);
      });
      
  });
  
});
