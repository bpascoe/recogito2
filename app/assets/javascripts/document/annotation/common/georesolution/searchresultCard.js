define([
  'common/ui/formatting',
  'common/utils/placeUtils',
  'common/hasEvents','common/api'], function(Formatting, PlaceUtils, HasEvents, API) {

  var SearchResultCard = function(ul, place) {

    var self = this,

        element = jQuery(
          '<li class="place-details">' +
            '<h3 class="title"></h3>' +
            '<p class="names"></p>' +
            '<p class="uris"></p>' +
            '<p class="description"></p>' +
            '<p class="date"></p>' +
            '<p class="unlocated"></p>' +
          '</li>'),

        titleEl       = element.find('.title'),
        namesEl       = element.find('.names'),
        urisEl        = element.find('.uris'),
        descriptionEl = element.find('.description'),
        dateEl        = element.find('.date'),
        unlocatedEl   = element.find('.unlocated'),

        formatURI = function(uri) {
          var data = PlaceUtils.parseURI(uri),
              char = (data.shortcode) ? data.shortcode.charAt(0).toUpperCase() : false;

          if (data.shortcode)
            return '<a class="minilink" href="' + uri + '" title="' +
              data.shortcode + ':' + data.id + '" style="background-color:' +
              data.color + '" target="_blank">' + char + '</a>';
          else
            return '<a class="minilink" href="' + uri + '" title="' +
              uri + '" target="_blank">?</a>';
        },

        render = function() {
          var uris = PlaceUtils.getURIs(place),
              titles = PlaceUtils.getTitles(place, true),
              names = PlaceUtils.getDistinctPlaceNames(place, { excludeTitles: true }),
              descriptions = PlaceUtils.getDescriptions(place);
          var gazetteer = place.is_conflation_of[0].source_authority;
          if (gazetteer == "contribution") {
            $.ajax({url:document.location.origin + "/api/place/contributor", type: 'POST',contentType: 'application/json',data:JSON.stringify({"unionId":uris[0]}),async: false,success: function(user) {
              gazetteer = gazetteer + " (" + user.split(",")[0] + ")";
            }});
            // gazetteer = gazetteer + ": " + place.contributor;
          }
          titleEl.html(titles.join(', ')+'<span style="float:right;">'+gazetteer+'</span>');
          namesEl.html(names.join(', '));

          jQuery.each(uris, function(idx, uri) {
            urisEl.append(formatURI(uri));
          });

          if (descriptions.length > 0)
            descriptionEl.html(descriptions[0].description);
          else
            descriptionEl.hide();

          if (place.temporal_bounds)
            dateEl.html(Formatting.yyyyMMddToYear(place.temporal_bounds.from) + ' - ' +
                        Formatting.yyyyMMddToYear(place.temporal_bounds.to));
          else
            dateEl.hide();

          if (!place.representative_point)
            unlocatedEl.html('<span><span class="icon">&#xf071;</span> Unlocated Place</span>');
          else
            unlocatedEl.hide();

          ul.append(element);
        };

    render();

    element.click(function() { self.fireEvent('click'); });

    HasEvents.apply(this);
  };
  SearchResultCard.prototype = Object.create(HasEvents.prototype);

  return SearchResultCard;

});
