define([
  'document/annotation/common/editor/sections/place/cards/baseCard','common/config',
  'common/api','document/annotation/common/page/header'], function(Card, Config, API, Header) {
  
  var NoMatchCard = function(containerEl, verificationStatus, lastModified) {
    var header = new Header();
    // TODO cover the case of yellow place status'es - different message + place overlay

    var noMatch = jQuery('<div class="no-match">' +
              '<div class="label"></div>' +
              '<button class="btn tiny change" title="Use advanced search to find a match">Search</button> ' +
              '<button class="btn tiny flag icon" title="Flag this place as unidentified">&#xf11d;</button> ' +
              '<button class="btn tiny delete icon" title="Not a place - remove">&#xf014;</button>' +
            '</div>'),
        addPlace = jQuery('<div class="add-place" style="margin-right: 4px;float: right;z-index: 3;margin-top: -140px;position: relative;">' +
            '<div style="padding-top:2px";><input type="text" class="title" placeholder="Add title" required value="'+$('#main .selection').text()+'"></input></div>' +
            '<div style="padding-top:2px;"><input type="text" class="uri" placeholder="Add uri" required></input></div>' +
            '<div style="padding-top:2px;"><input type="text" class="latitude" placeholder="Add latitude" required></input></div>' +
            '<div style="padding-top:2px;"><input type="text" class="longitude" placeholder="Add longitude" required></input></div>' +
            '<div><button class="btn-add-place">Manually add place here</button></div>' +
          '</div>'),
        // addButton = jQuery('<button class="btn tiny toggle" style="z-index: 3;position: fixed;margin-top: -24px;">Add palce manually</button>'),
        element = (Config.writeAccess) ? jQuery(
          '<div class="info-text">' +
            '<div class="no-match">' +
              '<div class="label"></div>' +
              '<button class="btn tiny change" title="Use advanced search to find a match">Search</button> ' +
              '<button class="btn tiny flag icon" title="Flag this place as unidentified">&#xf11d;</button> ' +
              '<button class="btn tiny delete icon" title="Not a place - remove">&#xf014;</button>' +
            '</div>' +
          '</div>') : jQuery(
          // Simplified version for read-only mode
          '<div class="info-text">' +
            '<div class="no-match readonly">' +
              '<div class="label"></div>' +
            '</div>' +
          '</div>'),
        // toggle = element.find('.toggle'),

        labelEl = element.find('.label'),

        btnFlag = element.find('.flag'),

        labelNoAutoMatch =
          '<h3>No automatic match found</h3>',

        labelFlagged =
          '<h3>Flagged as Not Identifiable</h3>' +
          '<p>A place no-one could resolve yet</p>',

        overlayFlagged =
          '<div class="map-overlay flagged">' +
            '<span class="icon">&#xe842;</span>' +
          '</div>',

        render = function() {
          containerEl.html(element);
          // containerEl.after(addButton);
          containerEl.after(addPlace);
          if (verificationStatus && verificationStatus.value === 'NOT_IDENTIFIABLE')
            setFlagged();
          else
            labelEl.html(labelNoAutoMatch);
        },

        setFlagged = function() {
          labelEl.html(labelFlagged);
          btnFlag.remove();
          containerEl.append(overlayFlagged);
        };

    this.render = render;
    this.setFlagged = setFlagged;

    Card.apply(this, [ element ]);

    render();

    addPlace.on('click', '.btn-add-place', function() {
      var title = addPlace.find(".title").val(),
          uri = addPlace.find(".uri").val(),
          lat = addPlace.find(".latitude").val(),
          lon = addPlace.find(".longitude").val(),
          jsonData = {'title':title, 'uri': uri, 'lat':parseFloat(lat), 'lon':parseFloat(lon)};
      if (title && uri && lat && lon)
        API.addPlace2Gazetter(jsonData).done(function(result) {
         if (result) //header.showStatusSaved();
           $('.ok').click();
        }).fail(function(error) {
         header.showSaveError(error);
        });
      else 
        alert("All information should be filled")
      });
  };
  NoMatchCard.prototype = Object.create(Card.prototype);

  return NoMatchCard;

});
