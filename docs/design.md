# EOS Camera Roll design

A photographic gallery based on the supplied mobile reference: three columns, square images, subtle 6dp corners, generous gutters and quiet floating navigation. Keep Android-native behavior, accessibility labels and 48dp touch targets. Do not add search, maps, sample dates or decorative content unrelated to the camera library.

Palette: canvas #0B0B0D, raised surface #19191D, primary text #F5F3F4, secondary #AAA5AA, dark-mode accent #FF777D, primary action #D92B35. Light mode uses #FAF9FA, white surfaces, #202025 text, #69636A secondary and #BC2030 accent. Gold is reserved for ratings, green for completed copies. Photos are never tinted or dimmed. Primary red buttons use white text; dark red selection backgrounds use pale red text.

Typography: native sans-serif-medium for the compact EOS Camera Roll title, medium weight tab labels and 12sp status. Photo details are revealed only in the viewer or information sheet.

Layout:

    [logo] EOS Camera Roll       [refresh] [more]
           Camera status
    [All] [Photos 742] [Videos 128]     [filters]

       square     square     square
       square     square     square
       ... recycled three-column grid ...

       [Library] [Rated] [Saved]        [save]

The library's shape and photos are the visual focus. Controls stay quiet until selection or transfer makes them relevant. Folder/rating controls belong in a filter sheet, copy actions in a download sheet, connection settings in their own sheet. The reference's floating navigation becomes Library / Rated / Saved, which match this product's actual tasks. Do not copy its irrelevant Map control or invent capture dates.

Cached thumbnails are available after restart. A restored library says it needs refresh, not that the camera is connected. A successful empty scan, an empty filter, an unreachable camera, missing hostname, authentication rejection and partial scan must remain different states. Keep old thumbnails visible through refresh and failure.

Show the camera thumbnail first. Embedded thumbnail EXIF and optional small Canon rotation JSON can normalize it. Never fetch an original or its header to populate the grid. If rotation is missing, the explicit photo viewer learns it from the original and replaces that cached thumbnail. Cached tiles never trigger network revalidation; visible thumbnail work outranks optional metadata.

Video viewers use a cached cover without making background thumbnail or metadata requests. Live playback requires validated byte ranges, bounded startup reads and a progress deadline. If streaming fails, offer Download & play in the chosen folder, then use the saved document URI. Never silently spool a full video. Stop and cancel on error, completion, navigation, backgrounding and refresh; native player release must not block the main thread.
