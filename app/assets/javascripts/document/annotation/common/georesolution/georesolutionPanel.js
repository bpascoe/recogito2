define([
  'document/annotation/common/georesolution/searchresultCard',
  'common/map/basemap',
  'common/ui/countries',
  'common/ui/formatting',
  'common/utils/placeUtils',
  'common/api',
  'common/hasEvents'
], function(ResultCard, BaseMap, Countries, Formatting, PlaceUtils, API, HasEvents) {

  var SHAPE_STYLE = {
        color:'#146593',
        fillColor:'#5397b0',
        opacity:1,
        weight:1.5,
        fillOpacity:0.7
      };

  var GeoresolutionPanel = function() {

    var self = this,

        element = (function() {
            var el = jQuery(
              '<div class="clicktrap">' +
                '<div class="modal-wrapper georesolution-wrapper">' +
                  '<div class="modal georesolution-panel">' +
                    '<div class="modal-header">' +
                      '<div class="georesolution-search">' +
                        '<input class="search inline" type="text" placeholder="Search for a Place..." />' +
                        '<button class="search icon">&#xf002;</button>' +
                      '</div>' +
                      '<div class="flag-this-place">' +
                        'Can\'t find the right match?' +
                        '<span class="flag">' +
                          '<button class="outline-icon nostyle">&#xe842;</button>' +
                          '<span class="label">Flag this place</span>' +
                        '</span>' +
                        '<span class="create-place">' +
                          '<b style="font-size:24px;margin-left:10px;margin-right:5px;">+</b>' +
                          '<span class="label">Create place</span>' +
                        '</span>' +
                      '</div>' +
                      '<button class="nostyle outline-icon cancel">&#xe897;</button>' +
                    '</div>' +
                    '<div class="modal-body">' +
                      '<div class="georesolution-sidebar">' +
                        '<div class="result-header">'+
                          '<div class="result-total">' +
                            '<span class="icon">&#xf03a;</span> ' +
                            '<span class="label"></span> ' +
                            '<span class="result-took"></span>' +
                          '</div>' +
                        '</div>' +
                        '<div class="result-list">' +
                          '<ul></ul>' +
                          '<div class="wait-for-next">' +
                            '<img src="/assets/images/wait-circle.gif">' +
                          '</div>' +
                        '</div>' +
                      '</div>' +
                      '<div class="map-container">' +
                        '<div class="map"></div>' +
                        '<div class="map-controls">' +
                          '<div class="layers control icon" title="Change base layer">&#xf0c9;</div>' +
                          '<div class="zoom">' +
                            '<div class="zoom-in control" title="Zoom in">+</div>' +
                            '<div class="zoom-out control" title="Zoom out">&ndash;</div>' +
                          '</div>' +
                        '</div>' +
                      '</div>' +
                    '</div>' +
                  '</div>' +
                '</div>' +
              '</div>');

            el.find('.wait-for-next').hide();
            el.find('.modal-wrapper').draggable({ handle: '.modal-header' });
            el.hide();
            jQuery(document.body).append(el);
            return el;
          })(),

          searchInput     = element.find('.search'),

          btnFlag         = element.find('.flag'),
          btnCancel       = element.find('.cancel'),

          resultTotal     = element.find('.result-total .label'),
          resultTook      = element.find('.result-took'),

          resultContainer = element.find('.result-list'),
          resultList      = element.find('.result-list ul'),
          waitForNext     = element.find('.wait-for-next'),

          mapContainer    = element.find('.map-container'),

          placeBody = false,

          /** Either the value of the search field, or the 'fuzzified' version, with ~ appended **/
          currentSearch = false,

          currentSearchResults = [],
          currentSearchResultsTotal,

          /**
           * The map popup is handled by Leaflet, but we need keep track
           * of the 'unlocated place' popup ourselves
           */
          unlocatedPopup = false,

          map = new BaseMap(element.find('.map')),

          markerLayer = L.featureGroup(),

          shapeLayer = L.featureGroup(),

          closeUnlocatedPopup = function() {
            if (unlocatedPopup) {
              unlocatedPopup.remove();
              unlocatedPopup = false;
              mapContainer.removeClass('unlocated');
            }
          },

          openUnlocatedPopup = function(popup) {
            var wrapper = jQuery(
                  '<div class="unlocated popup-wrapper">' +
                    '<div class="place-popup-content"></div>' +
                  '</div>');

            closeUnlocatedPopup();
            map.leafletMap.closePopup();
            mapContainer.addClass('unlocated');

            unlocatedPopup = wrapper;
            popup.prepend('<button class="nostyle outline-icon close">&#xe897;</button>');
            wrapper.find('.place-popup-content').append(popup);
            popup.on('click', '.close', closeUnlocatedPopup);
            mapContainer.append(wrapper);
          },

          openMapPopup = function(popup, marker) {
            marker.unbindPopup();
            closeUnlocatedPopup();
            marker.bindPopup(popup[0]).openPopup();
          },

          openPopup = function(place, opt_marker) {
            var titles = PlaceUtils.getTitles(place, true),

                popup = jQuery(
                  '<div class="popup">' +
                    '<div class="popup-header">' +
                      '<h3>' + titles.join(', ') + '</h3>' +
                    '</div>' +
                    '<div class="popup-choices"><table class="gazetteer-records"></table></div>' +
                  '</div>');

            place.is_conflation_of.reduce(function(previousShortcode, record) {
              var recordId = PlaceUtils.parseURI(record.uri),

                  title = (record.country_code) ?
                    record.title + ', ' + Countries.getName(record.country_code) :
                    record.title,

                  names = PlaceUtils.getDistinctRecordNames(record, { excludeTitles: true }),

                  template = jQuery(
                    '<tr data-uri="' + recordId.uri + '">' +
                      '<td class="record-id">' +
                        '<span class="shortcode"></span>' +
                        '<span class="id"></span>' +
                      '</td>' +
                      '<td class="place-details">' +
                        '<h3>' + title + '</h3>' +
                        '<p class="names">' + names.join(', ') + '</p>' +
                        '<p class="description"></p>' +
                        '<p class="date"></p>' +
                      '</td>' +
                    '</tr>');

              if (recordId.shortcode) {
                template.find('.shortcode').html(recordId.shortcode);
                template.find('.id').html(recordId.id);
                template.find('.record-id').css('background-color', recordId.color);

                if (previousShortcode === recordId.shortcode)
                  template.find('.record-id').css('border-top', '1px solid rgba(0, 0, 0, 0.05)');
              }

              if (record.descriptions && record.descriptions.length > 0)
                template.find('.description').html(record.descriptions[0].description);
              else
                template.find('.description').hide();

              if (record.temporal_bounds)
                template.find('.date').html(
                  Formatting.yyyyMMddToYear(record.temporal_bounds.from) + ' - ' +
                  Formatting.yyyyMMddToYear(record.temporal_bounds.to));
              else
                template.find('.date').hide();
              popup.find('.popup-choices table').append(template);

              if (recordId.shortcode) return recordId.shortcode;
            }, false);

            popup.on('click', 'tr', function(e) {
              var tr = jQuery(e.target).closest('tr');
              self.fireEvent('change', placeBody, {
                uri: tr.data('uri'),
                status: { value: 'VERIFIED' }
              });
              close();
            });

            if (opt_marker)
              openMapPopup(popup, opt_marker);
            else
              openUnlocatedPopup(popup);
          },

          onFlag = function() {
            self.fireEvent('change', placeBody, {
              uri: false,
              status: { value: 'NOT_IDENTIFIABLE' }
            });
            close();
          },

          onNextPage = function(response) {
            var createMarker = function(place) {
                  if (place.representative_geometry && place.representative_geometry.type !== 'Point')
                    return L.geoJSON(place.representative_geometry, SHAPE_STYLE).addTo(shapeLayer);
                  else if (place.representative_point)
                    return L.marker([place.representative_point[1], place.representative_point[0]]).addTo(markerLayer);
                },

                moreAvailable =
                  response.total > currentSearchResults.length + response.items.length;

            // Switch wait icon on/off
            if (moreAvailable)
              waitForNext.show();
            else
              waitForNext.hide();

            currentSearchResults = currentSearchResults.concat(response.items);
            currentSearchResultsTotal = response.total;

            resultTotal.html(response.total + ' Total');
            resultTook.html('Took ' + response.took + 'ms');

            jQuery.each(response.items, function(idx, place) {
              var result = new ResultCard(resultList, place),
                  marker = createMarker(place);

              // Click on the list item opens popup (on marker, if any)
              result.on('click', function() { openPopup(place, marker); });

              // If there's a marker, click on the marker opens popup
              if (marker)
                marker.on('click', function(e) { openPopup(place, marker); });
            });
          },

          /** If scrolled to bottom, we load the next result page if needed **/
          onScroll = function(e) {
            var scrollPos = resultContainer.scrollTop() + resultContainer.innerHeight(),
                scrollBottom = resultContainer[0].scrollHeight;

            if (scrollPos >= scrollBottom)
              if (currentSearchResultsTotal > currentSearchResults.length)
                search(currentSearchResults.length);
          },

          search = function(opt_offset) {
            var offset = (opt_offset) ? opt_offset : 0,

                endsWith = function(str, char) {
                  return str.indexOf(char, str.length -  char.length) !== -1;
                },

                onResponse = function(response) {
                  // Try again with a fuzzy search
                  if (response.total === 0 && !endsWith(currentSearch, '~')) {
                    currentSearch = currentSearch + '~';
                    API.searchPlaces(currentSearch, offset).done(onNextPage);
                  } else {
                    onNextPage(response);
                  }
                };

            if (currentSearch)
              API.searchPlaces(currentSearch, offset).done(onResponse);
          },

          clear = function() {
            markerLayer.clearLayers();
            shapeLayer.clearLayers();
            resultList.empty();
            resultTotal.empty();
            resultTook.empty();
            currentSearchResults = [];
            currentSearchResultsTotal = 0;
          },

          open = function(toponym, body) {
            placeBody = body;

            searchInput.val(toponym);
            currentSearch = toponym;
            element.show();
            map.refresh();

            clear();
            search();

            searchInput.get(0).focus();
          },

          close = function() {
            placeBody = false;
            closeUnlocatedPopup();
            waitForNext.hide();
            element.hide();
          },

          /**
           * This prevents the background text from scrolling once the bottom of the
           * search result list is reached.
           */
          blockMouseWheelBubbling = function() {
            element.bind('mousewheel', function(e) {
              if (e.originalEvent.wheelDelta) {
                var scrollTop = resultContainer.scrollTop(),
                    scrollPos = scrollTop + resultContainer.innerHeight(),
                    scrollBottom = resultContainer[0].scrollHeight,
                    d = e.originalEvent.wheelDelta;

                if ((scrollPos === scrollBottom && d < 0) || (scrollTop === 0 && d > 0))
                  e.preventDefault();
              }
            });
          };

    map.add(markerLayer);
    map.add(shapeLayer);
    var updateMarker = function(lat, lng) {
        var marker = L.marker([lat, lng]).addTo(markerLayer);
        $(".searchMark").remove();
        $(marker._icon).addClass('searchMark');
        marker.bindPopup("<p>Latitude is: " + lat + "<p>Longitude is: " + lng +"<p><button class='btn-ok-mark'>Add</button>")
        .openPopup();
        return false;
    };
    map.leafletMap.on('click', function(e) {
        updateMarker(e.latlng.lat, e.latlng.lng);
        $(".leaflet-popup-content .btn-ok-mark").on('click', function() {
          $(".btn-add-place").click();
          $(".clicktrap").hide();
          $('.latitude').val(e.latlng.lat);
          $('.longitude').val(e.latlng.lng);
        });
    });

    resultContainer.scroll(onScroll);

    element.on( "click", ".create-place", function() {
      if ($(".btn-add-place").length) {
        $(".btn-add-place").click();
        element.hide();
      } else {
        var addPlace = jQuery('<div class="wrapper wrapper--w680 add-place"><div class="card card-1"><div class="card-body">' +
            '<b class="title">Create Place </b><span style="font-size:12px; color:gray;">Fileds with * are required</span>'+
            '<div class="input-group"><input class="input--style-1 title2" type="text" placeholder="Place Name*" value="'+$('.info-text .title').text()+'" required></div>'+
            '<div class="input-group"><input class="input--style-1 uri" type="text" placeholder="URI" value="'+$('.info-text .uris a').attr("href")+'"></div>'+
            '<div class="input-group"><input class="input--style-1 latitude" type="text" placeholder="Latitude*" required value="'+$('.info-text .latitude2').text()+'"></div>'+
            '<div class="input-group"><input class="input--style-1 longitude" type="text" placeholder="Longitude*" required value="'+$('.info-text .longitude2').text()+'"></div>'+
            '<div class="input-group"><div class="rs-select2 js-select-simple select--no-search">'+
             '<select name="country" id="country"><option>Country</option></select>'+
             '<div class="select-dropdown"></div></div></div>'+
            '<div class="input-group"><input class="input--style-1 description" type="text" placeholder="Description" value="'+$('.info-text .description').text()+'"></div>'+
            '<div class="input-group"><input class="input--style-1 js-datepicker from" type="text" placeholder="Timespan Start yyyy-mm-dd" value="'+$('.info-text .from2').text()+'"></div>'+
            '<div class="input-group"><input class="input--style-1 js-datepicker2 to" type="text" placeholder="Timespan End yyyy-mm-dd" value="'+$('.info-text .to2').text()+'"></div>'+
            '<div class="input-group"><input class="input--style-1 altNames" type="text" placeholder="Alternate Names" value="'+$('.info-text .names').text()+'"></div>'+
            '<div class="p-t-20"><button class="btn btn--radius btn--red btn-cancel-place">Cancel</button>'+
            '<button class="btn btn--radius btn--green add-place-submit">Submit</button></div>'+
            '</div></div></div>'+
            // '<div><button class="btn-add-place">Create Place</button></div>' +
          '</div>');
        $.each(Countries.getCountries() , function(index, val) {
          var options = addPlace.find('select').prop('options');
          if (options.length == 1) {
            var ccode = $('.info-text .ccode').text();
            if (ccode) options[0] = new Option(Countries.getName(ccode), ccode);
          }
          options[options.length] = new Option(val, index);
        });
        
        $(".clicktrap").hide();
        $(".annotation-editor-popup").hide();
        $("#main").after(addPlace);
        $(".add-place" ).dialog();
        $(".add-place").prev().css("display","none");
        // datepicker
        $(".from" ).datepicker({
          dateFormat: 'yy-mm-dd',
          changeMonth: true,
          changeYear: true,
          showOn: "button",
          buttonImage: "https://jqueryui.com/resources/demos/datepicker/images/calendar.gif",
          buttonImageOnly: true,
          buttonText: "Select date",
          forceParse: false
        });
        $(".to" ).datepicker({
          dateFormat: 'yy-mm-dd',
          changeMonth: true,
          changeYear: true,
          showOn: "button",
          buttonImage: "https://jqueryui.com/resources/demos/datepicker/images/calendar.gif",
          buttonImageOnly: true,
          buttonText: "Select date",
          forceParse: false
        });
        $('.add-place').dialog({autoOpen: false,title: 'Create Place'});
        addPlace.on('click', '.add-place-submit', function() {
          var title = addPlace.find(".title2").val(),
              uri = addPlace.find(".uri").val(),
              lat = addPlace.find(".latitude").val(),
              lon = addPlace.find(".longitude").val(),
              from = addPlace.find(".from").val(),
              to = addPlace.find(".to").val(),
              ccode = addPlace.find("#country").val(),
              altNames = addPlace.find(".altNames").val(),
              description = addPlace.find(".description").val();
          if (!uri) uri = "http://www.tlcmap.org/" + window.location.hash.substring(1);
          var jsonData = {'title':title, 'uri': uri, 'lat':parseFloat(lat), 'lon':parseFloat(lon), 'ccode': ccode, 'from': from, 'to': to,'description':description,'altNames':altNames};
          if (title && lat && lon)
            API.addPlace2Gazetter(jsonData).done(function(result) {
             if (result) {
              sessionStorage.setItem("uri", uri);
              sessionStorage.setItem("title", title);
              $(".ui-dialog").remove();
              $('.ok').click();
              $(".add-place").remove();
            } 
             //header.showStatusSaved();
            }).fail(function(error) {
             header.showSaveError(error);
            });
          else 
            alert("All information should be filled")
          });
        
        addPlace.on( "click", ".btn-cancel-place", function() {
          $(".ui-dialog").remove();
          $(".add-place").remove();
          $(".annotation-editor-popup").show();
        });
        // remove add place dialog if click no in the dialog
        $(document).mouseup(function(e){
          var container = $(".ui-dialog");
          if(!container.is(e.target) && container.has(e.target).length === 0){
              if (!$("#ui-datepicker-div").is(e.target) && $("#ui-datepicker-div").has(e.target).length === 0) {
                container.remove();
                $(".add-place").remove();
              }
              // $(".annotation-editor-popup").show();
          }
        });
      }
    });

    btnFlag.click(onFlag);
    btnCancel.click(close);
    searchInput.keyup(function(e) {
      if (e.which === 13) {
        clear();
        currentSearch = searchInput.val().trim();
        if (currentSearch.length === 0)
          currentSearch = false;
        search();
      }
    });

    blockMouseWheelBubbling();

    this.open = open;
    HasEvents.apply(this);
  };
  GeoresolutionPanel.prototype = Object.create(HasEvents.prototype);

  return GeoresolutionPanel;

});
