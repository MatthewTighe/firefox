//! C API for mp4parse module.
//!
//! Parses ISO Base Media Format aka video/mp4 streams.
//!
//! # Examples
//!
//! ```rust
//! use std::io::Read;
//!
//! extern fn buf_read(buf: *mut u8, size: usize, userdata: *mut std::os::raw::c_void) -> isize {
//!    let mut input: &mut std::fs::File = unsafe { &mut *(userdata as *mut _) };
//!    let mut buf = unsafe { std::slice::from_raw_parts_mut(buf, size) };
//!    match input.read(&mut buf) {
//!        Ok(n) => n as isize,
//!        Err(_) => -1,
//!    }
//! }
//! let capi_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
//! let mut file = std::fs::File::open(capi_dir + "/../mp4parse/tests/minimal.mp4").unwrap();
//! let io = mp4parse_capi::Mp4parseIo {
//!     read: Some(buf_read),
//!     userdata: &mut file as *mut _ as *mut std::os::raw::c_void
//! };
//! let mut parser = std::ptr::null_mut();
//! unsafe {
//!     let rv = mp4parse_capi::mp4parse_new(&io, &mut parser);
//!     assert_eq!(rv, mp4parse_capi::Mp4parseStatus::Ok);
//!     assert!(!parser.is_null());
//!     mp4parse_capi::mp4parse_free(parser);
//! }
//! ```

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use byteorder::WriteBytesExt;
use mp4parse::unstable::rational_scale;
use std::convert::TryFrom;
use std::convert::TryInto;

use std::io::Read;

// Symbols we need from our rust api.
use mp4parse::serialize_opus_header;
use mp4parse::unstable::{create_sample_table, CheckedInteger, Indice};
use mp4parse::AV1ConfigBox;
use mp4parse::AudioCodecSpecific;
use mp4parse::AvifContext;
use mp4parse::CodecType;
use mp4parse::MediaContext;
// Re-exported so consumers don't have to depend on mp4parse as well
pub use mp4parse::ParseStrictness;
use mp4parse::SampleEntry;
pub use mp4parse::Status as Mp4parseStatus;
use mp4parse::Track;
use mp4parse::TrackType;
use mp4parse::TryBox;
use mp4parse::TryHashMap;
use mp4parse::TryVec;
use mp4parse::VideoCodecSpecific;

// To ensure we don't use stdlib allocating types by accident
#[allow(dead_code)]
struct Vec;
#[allow(dead_code)]
struct Box;
#[allow(dead_code)]
struct HashMap;
#[allow(dead_code)]
struct String;

#[repr(C)]
#[derive(PartialEq, Eq, Debug, Default)]
pub enum Mp4parseTrackType {
    #[default]
    Video = 0,
    Picture = 1,
    AuxiliaryVideo = 2,
    Audio = 3,
    Metadata = 4,
}

#[allow(non_camel_case_types, clippy::upper_case_acronyms)]
#[repr(C)]
#[derive(PartialEq, Eq, Debug, Default)]
pub enum Mp4parseCodec {
    #[default]
    Unknown,
    Aac,
    Flac,
    Opus,
    Avc,
    Vp9,
    Av1,
    Mp3,
    Mp4v,
    Jpeg, // for QT JPEG atom in video track
    Ac3,
    Ec3,
    Alac,
    H263,
    Hevc,
    #[cfg(feature = "3gpp")]
    AMRNB,
    #[cfg(feature = "3gpp")]
    AMRWB,
    XHEAAC, // xHE-AAC (Extended High Efficiency AAC)
}

#[repr(C)]
#[derive(PartialEq, Eq, Debug, Default)]
pub enum Mp4ParseEncryptionSchemeType {
    #[default]
    None,
    Cenc,
    Cbc1,
    Cens,
    Cbcs,
    // Schemes also have a version component. At the time of writing, this does
    // not impact handling, so we do not expose it. Note that this may need to
    // be exposed in future, should the spec change.
}

#[repr(C)]
#[derive(Default, Debug)]
pub struct Mp4parseTrackInfo {
    pub track_type: Mp4parseTrackType,
    pub track_id: u32,
    pub duration: u64,
    pub media_time: CheckedInteger<i64>,
    pub time_scale: u32,
}

#[repr(C)]
#[derive(Debug)]
pub struct Mp4parseByteData {
    pub length: usize,
    // cheddar can't handle generic type, so it needs to be multiple data types here.
    pub data: *const u8,
    pub indices: *const Indice,
}

impl Mp4parseByteData {
    fn with_data(slice: &[u8]) -> Self {
        Self {
            length: slice.len(),
            data: if !slice.is_empty() {
                slice.as_ptr()
            } else {
                std::ptr::null()
            },
            indices: std::ptr::null(),
        }
    }
}

impl Default for Mp4parseByteData {
    fn default() -> Self {
        Self {
            length: 0,
            data: std::ptr::null(),
            indices: std::ptr::null(),
        }
    }
}

impl Mp4parseByteData {
    fn set_data(&mut self, data: &[u8]) {
        self.length = data.len();
        self.data = data.as_ptr();
    }

    fn set_indices(&mut self, data: &[Indice]) {
        self.length = data.len();
        self.indices = data.as_ptr();
    }
}

#[repr(C)]
#[derive(Default)]
pub struct Mp4parsePsshInfo {
    pub data: Mp4parseByteData,
}

#[repr(u8)]
#[derive(Debug, PartialEq, Eq)]
pub enum OptionalFourCc {
    None,
    Some([u8; 4]),
}

impl Default for OptionalFourCc {
    fn default() -> Self {
        Self::None
    }
}

#[repr(C)]
#[derive(Default, Debug)]
pub struct Mp4parseSinfInfo {
    pub original_format: OptionalFourCc,
    pub scheme_type: Mp4ParseEncryptionSchemeType,
    pub is_encrypted: u8,
    pub iv_size: u8,
    pub kid: Mp4parseByteData,
    // Members for pattern encryption schemes, may be 0 (u8) or empty
    // (Mp4parseByteData) if pattern encryption is not in use
    pub crypt_byte_block: u8,
    pub skip_byte_block: u8,
    pub constant_iv: Mp4parseByteData,
    // End pattern encryption scheme members
}

#[repr(C)]
#[derive(Default, Debug)]
pub struct Mp4parseTrackAudioSampleInfo {
    pub codec_type: Mp4parseCodec,
    pub channels: u16,
    pub bit_depth: u16,
    pub sample_rate: u32,
    pub profile: u16,
    pub extended_profile: u16,
    pub codec_specific_config: Mp4parseByteData,
    pub extra_data: Mp4parseByteData,
    pub protected_data: Mp4parseSinfInfo,
}

#[repr(C)]
#[derive(Debug)]
pub struct Mp4parseTrackAudioInfo {
    pub sample_info_count: u32,
    pub sample_info: *const Mp4parseTrackAudioSampleInfo,
}

impl Default for Mp4parseTrackAudioInfo {
    fn default() -> Self {
        Self {
            sample_info_count: 0,
            sample_info: std::ptr::null(),
        }
    }
}

#[repr(C)]
#[derive(Default, Debug)]
pub struct Mp4parseTrackVideoSampleInfo {
    pub codec_type: Mp4parseCodec,
    pub image_width: u16,
    pub image_height: u16,
    pub extra_data: Mp4parseByteData,
    pub protected_data: Mp4parseSinfInfo,
}

#[repr(C)]
#[derive(Debug)]
pub struct Mp4parseTrackVideoInfo {
    pub display_width: u32,
    pub display_height: u32,
    pub rotation: u16,
    pub sample_info_count: u32,
    pub sample_info: *const Mp4parseTrackVideoSampleInfo,
    pub pixel_aspect_ratio: f32,
}

impl Default for Mp4parseTrackVideoInfo {
    fn default() -> Self {
        Self {
            display_width: 0,
            display_height: 0,
            rotation: 0,
            sample_info_count: 0,
            sample_info: std::ptr::null(),
            pixel_aspect_ratio: 0.0,
        }
    }
}

#[repr(C)]
#[derive(Default, Debug)]
pub struct Mp4parseFragmentInfo {
    pub fragment_duration: u64, // in ticks
    pub time_scale: u64,
    // TODO:
    // info in trex box.
}

#[derive(Default)]
pub struct Mp4parseParser {
    context: MediaContext,
    opus_header: TryHashMap<u32, TryVec<u8>>,
    pssh_data: TryVec<u8>,
    sample_table: TryHashMap<u32, TryVec<Indice>>,
    // Store a mapping from track index (not id) to associated sample
    // descriptions. Because each track has a variable number of sample
    // descriptions, and because we need the data to live long enough to be
    // copied out by callers, we store these on the parser struct.
    audio_track_sample_descriptions: TryHashMap<u32, TryVec<Mp4parseTrackAudioSampleInfo>>,
    video_track_sample_descriptions: TryHashMap<u32, TryVec<Mp4parseTrackVideoSampleInfo>>,
}

#[repr(C)]
#[derive(Debug, Default)]
pub enum Mp4parseAvifLoopMode {
    #[default]
    NoEdits,
    LoopByCount,
    LoopInfinitely,
}

#[repr(C)]
#[derive(Debug)]
pub struct Mp4parseAvifInfo {
    pub premultiplied_alpha: bool,
    pub major_brand: [u8; 4],
    pub unsupported_features_bitfield: u32,
    /// The size of the image; should never be null unless using permissive parsing
    pub spatial_extents: *const mp4parse::ImageSpatialExtentsProperty,
    pub nclx_colour_information: *const mp4parse::NclxColourInformation,
    pub icc_colour_information: Mp4parseByteData,
    pub image_rotation: mp4parse::ImageRotation,
    pub image_mirror: *const mp4parse::ImageMirror,
    pub pixel_aspect_ratio: *const mp4parse::PixelAspectRatio,

    /// Whether there is a `pitm` reference to the color image present.
    pub has_primary_item: bool,
    /// Bit depth for the item referenced by `pitm`, or 0 if values are inconsistent.
    pub primary_item_bit_depth: u8,
    /// Whether there is an `auxl` reference to the `pitm`-accompanying
    /// alpha image present.
    pub has_alpha_item: bool,
    /// Bit depth for the alpha item used by the `pitm`, or 0 if values are inconsistent.
    pub alpha_item_bit_depth: u8,

    /// Whether there is a sequence. Can be true with no primary image.
    pub has_sequence: bool,
    /// Indicates whether the EditListBox requests that the image be looped.
    pub loop_mode: Mp4parseAvifLoopMode,
    /// Number of times to loop the animation during playback.
    ///
    /// The duration of the animation specified in `elst` must be looped to fill the
    /// duration of the color track. If the resulting loop count is not an integer,
    /// then it will be ceiled to play past and fill the entire track's duration.
    pub loop_count: u64,
    /// The color track's ID, which must be valid if has_sequence is true.
    pub color_track_id: u32,
    pub color_track_bit_depth: u8,
    /// The track ID of the alpha track, will be 0 if no alpha track is present.
    pub alpha_track_id: u32,
    pub alpha_track_bit_depth: u8,
}

#[repr(C)]
#[derive(Debug)]
pub struct Mp4parseAvifImage {
    pub primary_image: Mp4parseByteData,
    /// If no alpha item exists, members' `.length` will be 0 and `.data` will be null
    pub alpha_image: Mp4parseByteData,
}

/// A unified interface for the parsers which have different contexts, but
/// share the same pattern of construction. This allows unification of
/// argument validation from C and minimizes the surface of unsafe code.
trait ContextParser
where
    Self: Sized,
{
    type Context;

    fn with_context(context: Self::Context) -> Self;

    fn read<T: Read>(io: &mut T, strictness: ParseStrictness) -> mp4parse::Result<Self::Context>;
}

impl Mp4parseParser {
    fn context(&self) -> &MediaContext {
        &self.context
    }

    fn context_mut(&mut self) -> &mut MediaContext {
        &mut self.context
    }
}

impl ContextParser for Mp4parseParser {
    type Context = MediaContext;

    fn with_context(context: Self::Context) -> Self {
        Self {
            context,
            ..Default::default()
        }
    }

    fn read<T: Read>(io: &mut T, strictness: ParseStrictness) -> mp4parse::Result<Self::Context> {
        let r = mp4parse::read_mp4(io, strictness);
        log::debug!("mp4parse::read_mp4 -> {r:?}");
        r
    }
}

#[derive(Default)]
pub struct Mp4parseAvifParser {
    context: AvifContext,
    sample_table: TryHashMap<u32, TryVec<Indice>>,
}

impl Mp4parseAvifParser {
    fn context(&self) -> &AvifContext {
        &self.context
    }
}

impl ContextParser for Mp4parseAvifParser {
    type Context = AvifContext;

    fn with_context(context: Self::Context) -> Self {
        Self {
            context,
            ..Default::default()
        }
    }

    fn read<T: Read>(io: &mut T, strictness: ParseStrictness) -> mp4parse::Result<Self::Context> {
        let r = mp4parse::read_avif(io, strictness);
        if r.is_err() {
            log::debug!("{r:?}");
        }
        log::trace!("mp4parse::read_avif -> {r:?}");
        r
    }
}

#[repr(C)]
#[derive(Clone)]
pub struct Mp4parseIo {
    pub read: Option<
        extern "C" fn(buffer: *mut u8, size: usize, userdata: *mut std::os::raw::c_void) -> isize,
    >,
    pub userdata: *mut std::os::raw::c_void,
}

impl Read for Mp4parseIo {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if buf.len() > isize::MAX as usize {
            return Err(std::io::Error::other(
                "buf length overflow in Mp4parseIo Read impl",
            ));
        }
        let rv = self.read.unwrap()(buf.as_mut_ptr(), buf.len(), self.userdata);
        if rv >= 0 {
            Ok(rv as usize)
        } else {
            Err(std::io::Error::other("I/O error in Mp4parseIo Read impl"))
        }
    }
}

// C API wrapper functions.

/// Allocate an `Mp4parseParser*` to read from the supplied `Mp4parseIo` and
/// parse the content from the `Mp4parseIo` argument until EOF or error.
///
/// # Safety
///
/// This function is unsafe because it dereferences the `io` and `parser_out`
/// pointers given to it. The caller should ensure that the `Mp4ParseIo`
/// struct passed in is a valid pointer. The caller should also ensure the
/// members of io are valid: the `read` function should be sanely implemented,
/// and the `userdata` pointer should be valid. The `parser_out` should be a
/// valid pointer to a location containing a null pointer. Upon successful
/// return (`Mp4parseStatus::Ok`), that location will contain the address of
/// an `Mp4parseParser` allocated by this function.
///
/// To avoid leaking memory, any successful return of this function must be
/// paired with a call to `mp4parse_free`. In the event of error, no memory
/// will be allocated and `mp4parse_free` must *not* be called.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_new(
    io: *const Mp4parseIo,
    parser_out: *mut *mut Mp4parseParser,
) -> Mp4parseStatus {
    mp4parse_new_common(io, ParseStrictness::Normal, parser_out)
}

/// Allocate an `Mp4parseAvifParser*` to read from the supplied `Mp4parseIo`.
///
/// See mp4parse_new; this function is identical except that it allocates an
/// `Mp4parseAvifParser`, which (when successful) must be paired with a call
/// to mp4parse_avif_free.
///
/// # Safety
///
/// Same as mp4parse_new.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_avif_new(
    io: *const Mp4parseIo,
    strictness: ParseStrictness,
    parser_out: *mut *mut Mp4parseAvifParser,
) -> Mp4parseStatus {
    mp4parse_new_common(io, strictness, parser_out)
}

unsafe fn mp4parse_new_common<P: ContextParser>(
    io: *const Mp4parseIo,
    strictness: ParseStrictness,
    parser_out: *mut *mut P,
) -> Mp4parseStatus {
    // Validate arguments from C.
    if io.is_null()
        || (*io).userdata.is_null()
        || (*io).read.is_none()
        || parser_out.is_null()
        || !(*parser_out).is_null()
    {
        Mp4parseStatus::BadArg
    } else {
        match mp4parse_new_common_safe(&mut (*io).clone(), strictness) {
            Ok(parser) => {
                *parser_out = parser;
                Mp4parseStatus::Ok
            }
            Err(status) => status,
        }
    }
}

fn mp4parse_new_common_safe<T: Read, P: ContextParser>(
    io: &mut T,
    strictness: ParseStrictness,
) -> Result<*mut P, Mp4parseStatus> {
    P::read(io, strictness)
        .map(P::with_context)
        .and_then(|x| TryBox::try_new(x).map_err(mp4parse::Error::from))
        .map(TryBox::into_raw)
        .map_err(Mp4parseStatus::from)
}

/// Free an `Mp4parseParser*` allocated by `mp4parse_new()`.
///
/// # Safety
///
/// This function is unsafe because it creates a box from a raw pointer.
/// Callers should ensure that the parser pointer points to a valid
/// `Mp4parseParser` created by `mp4parse_new`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_free(parser: *mut Mp4parseParser) {
    assert!(!parser.is_null());
    let _ = TryBox::from_raw(parser);
}

/// Free an `Mp4parseAvifParser*` allocated by `mp4parse_avif_new()`.
///
/// # Safety
///
/// This function is unsafe because it creates a box from a raw pointer.
/// Callers should ensure that the parser pointer points to a valid
/// `Mp4parseAvifParser` created by `mp4parse_avif_new`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_avif_free(parser: *mut Mp4parseAvifParser) {
    assert!(!parser.is_null());
    let _ = TryBox::from_raw(parser);
}

/// Return the number of tracks parsed by previous `mp4parse_read()` call.
///
/// # Safety
///
/// This function is unsafe because it dereferences both the parser and count
/// raw pointers passed into it. Callers should ensure the parser pointer
/// points to a valid `Mp4parseParser`, and that the count pointer points an
/// appropriate memory location to have a `u32` written to.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_get_track_count(
    parser: *const Mp4parseParser,
    count: *mut u32,
) -> Mp4parseStatus {
    // Validate arguments from C.
    if parser.is_null() || count.is_null() {
        return Mp4parseStatus::BadArg;
    }
    let context = (*parser).context();

    // Make sure the track count fits in a u32.
    if context.tracks.len() > u32::MAX as usize {
        return Mp4parseStatus::Invalid;
    }
    *count = context.tracks.len() as u32;
    Mp4parseStatus::Ok
}

/// Fill the supplied `Mp4parseTrackInfo` with metadata for `track`.
///
/// # Safety
///
/// This function is unsafe because it dereferences the the parser and info raw
/// pointers passed to it. Callers should ensure the parser pointer points to a
/// valid `Mp4parseParser` and that the info pointer points to a valid
/// `Mp4parseTrackInfo`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_get_track_info(
    parser: *mut Mp4parseParser,
    track_index: u32,
    info: *mut Mp4parseTrackInfo,
) -> Mp4parseStatus {
    if parser.is_null() || info.is_null() {
        return Mp4parseStatus::BadArg;
    }

    // Initialize fields to default values to ensure all fields are always valid.
    *info = Default::default();

    let context = (*parser).context_mut();
    let track_index: usize = track_index as usize;
    let info: &mut Mp4parseTrackInfo = &mut *info;

    if track_index >= context.tracks.len() {
        return Mp4parseStatus::BadArg;
    }

    info.track_type = match context.tracks[track_index].track_type {
        TrackType::Video => Mp4parseTrackType::Video,
        TrackType::Picture => Mp4parseTrackType::Picture,
        TrackType::AuxiliaryVideo => Mp4parseTrackType::AuxiliaryVideo,
        TrackType::Audio => Mp4parseTrackType::Audio,
        TrackType::Metadata => Mp4parseTrackType::Metadata,
        TrackType::Unknown => return Mp4parseStatus::Unsupported,
    };

    let track = &context.tracks[track_index];

    if let (Some(timescale), Some(context_timescale)) = (track.timescale, context.timescale) {
        info.time_scale = timescale.0 as u32;
        let media_time: CheckedInteger<u64> = track
            .media_time
            .map_or(0.into(), |media_time| media_time.0.into());

        // Empty duration is in the context's timescale, convert it and return it in the track's
        // timescale
        let empty_duration: CheckedInteger<u64> =
            match track.empty_duration.map_or(Some(0), |empty_duration| {
                rational_scale(empty_duration.0, context_timescale.0, timescale.0)
            }) {
                Some(time) => mp4parse::unstable::CheckedInteger(time),
                None => return Mp4parseStatus::Invalid,
            };

        info.media_time = match media_time - empty_duration {
            Some(difference) => difference,
            None => return Mp4parseStatus::Invalid,
        };

        match track.duration {
            Some(duration) => info.duration = duration.0,
            None => {
                // Duration unknown; stagefright returns 0 for this.
                info.duration = 0
            }
        }
    } else {
        return Mp4parseStatus::Invalid;
    }

    info.track_id = match track.track_id {
        Some(track_id) => track_id,
        None => return Mp4parseStatus::Invalid,
    };
    Mp4parseStatus::Ok
}

/// Fill the supplied `Mp4parseTrackAudioInfo` with metadata for `track`.
///
/// # Safety
///
/// This function is unsafe because it dereferences the the parser and info raw
/// pointers passed to it. Callers should ensure the parser pointer points to a
/// valid `Mp4parseParser` and that the info pointer points to a valid
/// `Mp4parseTrackAudioInfo`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_get_track_audio_info(
    parser: *mut Mp4parseParser,
    track_index: u32,
    info: *mut Mp4parseTrackAudioInfo,
) -> Mp4parseStatus {
    if parser.is_null() || info.is_null() {
        return Mp4parseStatus::BadArg;
    }

    // Initialize fields to default values to ensure all fields are always valid.
    *info = Default::default();

    get_track_audio_info(&mut *parser, track_index, &mut *info).into()
}

fn get_track_audio_info(
    parser: &mut Mp4parseParser,
    track_index: u32,
    info: &mut Mp4parseTrackAudioInfo,
) -> Result<(), Mp4parseStatus> {
    let Mp4parseParser {
        context,
        opus_header,
        ..
    } = parser;

    if track_index as usize >= context.tracks.len() {
        return Err(Mp4parseStatus::BadArg);
    }

    let track = &context.tracks[track_index as usize];

    if track.track_type != TrackType::Audio {
        return Err(Mp4parseStatus::Invalid);
    }

    // Handle track.stsd
    let stsd = match track.stsd {
        Some(ref stsd) => stsd,
        None => return Err(Mp4parseStatus::Invalid), // Stsd should be present
    };

    if stsd.descriptions.is_empty() {
        return Err(Mp4parseStatus::Invalid); // Should have at least 1 description
    }

    let mut audio_sample_infos = TryVec::with_capacity(stsd.descriptions.len())?;
    for description in stsd.descriptions.iter() {
        let mut sample_info = Mp4parseTrackAudioSampleInfo::default();
        let audio = match description {
            SampleEntry::Audio(a) => a,
            _ => return Err(Mp4parseStatus::Invalid),
        };

        // UNKNOWN for unsupported format.
        sample_info.codec_type = match audio.codec_specific {
            AudioCodecSpecific::OpusSpecificBox(_) => Mp4parseCodec::Opus,
            AudioCodecSpecific::FLACSpecificBox(_) => Mp4parseCodec::Flac,
            AudioCodecSpecific::ES_Descriptor(ref esds) if esds.audio_codec == CodecType::AAC => {
                Mp4parseCodec::Aac
            }
            AudioCodecSpecific::ES_Descriptor(ref esds)
                if esds.audio_codec == CodecType::XHEAAC =>
            {
                Mp4parseCodec::XHEAAC
            }
            AudioCodecSpecific::ES_Descriptor(ref esds) if esds.audio_codec == CodecType::MP3 => {
                Mp4parseCodec::Mp3
            }
            AudioCodecSpecific::ES_Descriptor(_) | AudioCodecSpecific::LPCM => {
                Mp4parseCodec::Unknown
            }
            AudioCodecSpecific::MP3 => Mp4parseCodec::Mp3,
            AudioCodecSpecific::ALACSpecificBox(_) => Mp4parseCodec::Alac,
            #[cfg(feature = "3gpp")]
            AudioCodecSpecific::AMRSpecificBox(_) => {
                if audio.codec_type == CodecType::AMRNB {
                    Mp4parseCodec::AMRNB
                } else {
                    Mp4parseCodec::AMRWB
                }
            }
        };
        sample_info.channels = audio.channelcount as u16;
        sample_info.bit_depth = audio.samplesize;
        sample_info.sample_rate = audio.samplerate as u32;
        // sample_info.profile is handled below on a per case basis

        match audio.codec_specific {
            AudioCodecSpecific::ES_Descriptor(ref esds) => {
                if esds.codec_esds.len() > u32::MAX as usize {
                    return Err(Mp4parseStatus::Invalid);
                }
                sample_info.extra_data.length = esds.codec_esds.len();
                sample_info.extra_data.data = esds.codec_esds.as_ptr();
                sample_info.codec_specific_config.length = esds.decoder_specific_data.len();
                sample_info.codec_specific_config.data = esds.decoder_specific_data.as_ptr();
                if let Some(rate) = esds.audio_sample_rate {
                    sample_info.sample_rate = rate;
                }
                if let Some(channels) = esds.audio_channel_count {
                    sample_info.channels = channels;
                }
                if let Some(profile) = esds.audio_object_type {
                    sample_info.profile = profile;
                }
                sample_info.extended_profile = match esds.extended_audio_object_type {
                    Some(extended_profile) => extended_profile,
                    _ => sample_info.profile,
                };
            }
            AudioCodecSpecific::FLACSpecificBox(ref flac) => {
                // Return the STREAMINFO metadata block in the codec_specific.
                let streaminfo = &flac.blocks[0];
                if streaminfo.block_type != 0 || streaminfo.data.len() != 34 {
                    return Err(Mp4parseStatus::Invalid);
                }
                sample_info.codec_specific_config.length = streaminfo.data.len();
                sample_info.codec_specific_config.data = streaminfo.data.as_ptr();
            }
            AudioCodecSpecific::OpusSpecificBox(ref opus) => {
                let mut v = TryVec::new();
                match serialize_opus_header(opus, &mut v) {
                    Err(_) => {
                        return Err(Mp4parseStatus::Invalid);
                    }
                    Ok(_) => {
                        opus_header.insert(track_index, v)?;
                        if let Some(v) = opus_header.get(&track_index) {
                            if v.len() > u32::MAX as usize {
                                return Err(Mp4parseStatus::Invalid);
                            }
                            sample_info.codec_specific_config.length = v.len();
                            sample_info.codec_specific_config.data = v.as_ptr();
                        }
                    }
                }
            }
            AudioCodecSpecific::ALACSpecificBox(ref alac) => {
                sample_info.codec_specific_config.length = alac.data.len();
                sample_info.codec_specific_config.data = alac.data.as_ptr();
            }
            AudioCodecSpecific::MP3 | AudioCodecSpecific::LPCM => (),
            #[cfg(feature = "3gpp")]
            AudioCodecSpecific::AMRSpecificBox(_) => (),
        }

        if let Some(p) = audio
            .protection_info
            .iter()
            .find(|sinf| sinf.tenc.is_some())
        {
            sample_info.protected_data.original_format =
                OptionalFourCc::Some(p.original_format.value);
            sample_info.protected_data.scheme_type = match p.scheme_type {
                Some(ref scheme_type_box) => {
                    match scheme_type_box.scheme_type.value.as_ref() {
                        b"cenc" => Mp4ParseEncryptionSchemeType::Cenc,
                        b"cbcs" => Mp4ParseEncryptionSchemeType::Cbcs,
                        // We don't support other schemes, and shouldn't reach
                        // this case. Try to gracefully handle by treating as
                        // no encryption case.
                        _ => Mp4ParseEncryptionSchemeType::None,
                    }
                }
                None => Mp4ParseEncryptionSchemeType::None,
            };
            if let Some(ref tenc) = p.tenc {
                sample_info.protected_data.is_encrypted = tenc.is_encrypted;
                sample_info.protected_data.iv_size = tenc.iv_size;
                sample_info.protected_data.kid.set_data(&(tenc.kid));
                sample_info.protected_data.crypt_byte_block =
                    tenc.crypt_byte_block_count.unwrap_or(0);
                sample_info.protected_data.skip_byte_block =
                    tenc.skip_byte_block_count.unwrap_or(0);
                if let Some(ref iv_vec) = tenc.constant_iv {
                    if iv_vec.len() > u32::MAX as usize {
                        return Err(Mp4parseStatus::Invalid);
                    }
                    sample_info.protected_data.constant_iv.set_data(iv_vec);
                };
            }
        }
        audio_sample_infos.push(sample_info)?;
    }

    parser
        .audio_track_sample_descriptions
        .insert(track_index, audio_sample_infos)?;
    match parser.audio_track_sample_descriptions.get(&track_index) {
        Some(sample_info) => {
            if sample_info.len() > u32::MAX as usize {
                // Should never happen due to upper limits on number of sample
                // descriptions a track can have, but lets be safe.
                return Err(Mp4parseStatus::Invalid);
            }
            info.sample_info_count = sample_info.len() as u32;
            info.sample_info = sample_info.as_ptr();
        }
        None => return Err(Mp4parseStatus::Invalid), // Shouldn't happen, we just inserted the info!
    }

    Ok(())
}

/// Fill the supplied `Mp4parseTrackVideoInfo` with metadata for `track`.
///
/// # Safety
///
/// This function is unsafe because it dereferences the the parser and info raw
/// pointers passed to it. Callers should ensure the parser pointer points to a
/// valid `Mp4parseParser` and that the info pointer points to a valid
/// `Mp4parseTrackVideoInfo`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_get_track_video_info(
    parser: *mut Mp4parseParser,
    track_index: u32,
    info: *mut Mp4parseTrackVideoInfo,
) -> Mp4parseStatus {
    if parser.is_null() || info.is_null() {
        return Mp4parseStatus::BadArg;
    }

    // Initialize fields to default values to ensure all fields are always valid.
    *info = Default::default();

    mp4parse_get_track_video_info_safe(&mut *parser, track_index, &mut *info).into()
}

fn mp4parse_get_track_video_info_safe(
    parser: &mut Mp4parseParser,
    track_index: u32,
    info: &mut Mp4parseTrackVideoInfo,
) -> Result<(), Mp4parseStatus> {
    let context = parser.context();

    if track_index as usize >= context.tracks.len() {
        return Err(Mp4parseStatus::BadArg);
    }

    let track = &context.tracks[track_index as usize];

    if track.track_type != TrackType::Video {
        return Err(Mp4parseStatus::Invalid);
    }

    // Handle track.tkhd
    if let Some(ref tkhd) = track.tkhd {
        info.display_width = tkhd.width >> 16; // 16.16 fixed point
        info.display_height = tkhd.height >> 16; // 16.16 fixed point
        let matrix = (
            tkhd.matrix.a >> 16,
            tkhd.matrix.b >> 16,
            tkhd.matrix.c >> 16,
            tkhd.matrix.d >> 16,
        );
        info.rotation = match matrix {
            (0, 1, -1, 0) => 90,   // rotate 90 degrees
            (-1, 0, 0, -1) => 180, // rotate 180 degrees
            (0, -1, 1, 0) => 270,  // rotate 270 degrees
            _ => 0,
        };
    } else {
        return Err(Mp4parseStatus::Invalid);
    }

    // Handle track.stsd
    let stsd = match track.stsd {
        Some(ref stsd) => stsd,
        None => return Err(Mp4parseStatus::Invalid), // Stsd should be present
    };

    if stsd.descriptions.is_empty() {
        return Err(Mp4parseStatus::Invalid); // Should have at least 1 description
    }

    let mut video_sample_infos = TryVec::with_capacity(stsd.descriptions.len())?;
    for description in stsd.descriptions.iter() {
        let mut sample_info = Mp4parseTrackVideoSampleInfo::default();
        let video = match description {
            SampleEntry::Video(v) => v,
            _ => return Err(Mp4parseStatus::Invalid),
        };

        // UNKNOWN for unsupported format.
        sample_info.codec_type = match video.codec_specific {
            VideoCodecSpecific::VPxConfig(_) => Mp4parseCodec::Vp9,
            VideoCodecSpecific::AV1Config(_) => Mp4parseCodec::Av1,
            VideoCodecSpecific::AVCConfig(_) => Mp4parseCodec::Avc,
            VideoCodecSpecific::H263Config(_) => Mp4parseCodec::H263,
            VideoCodecSpecific::HEVCConfig(_) => Mp4parseCodec::Hevc,
            #[cfg(feature = "mp4v")]
            VideoCodecSpecific::ESDSConfig(_) => Mp4parseCodec::Mp4v,
            #[cfg(not(feature = "mp4v"))]
            VideoCodecSpecific::ESDSConfig(_) =>
            // MP4V (14496-2) video is unsupported.
            {
                Mp4parseCodec::Unknown
            }
        };
        sample_info.image_width = video.width;
        sample_info.image_height = video.height;
        if let Some(ratio) = video.pixel_aspect_ratio {
            info.pixel_aspect_ratio = ratio;
        }

        match video.codec_specific {
            VideoCodecSpecific::AV1Config(ref config) => {
                sample_info.extra_data.set_data(&config.raw_config);
            }
            VideoCodecSpecific::AVCConfig(ref data)
            | VideoCodecSpecific::ESDSConfig(ref data)
            | VideoCodecSpecific::HEVCConfig(ref data) => {
                sample_info.extra_data.set_data(data);
            }
            _ => {}
        }

        if let Some(p) = video
            .protection_info
            .iter()
            .find(|sinf| sinf.tenc.is_some())
        {
            sample_info.protected_data.original_format =
                OptionalFourCc::Some(p.original_format.value);
            sample_info.protected_data.scheme_type = match p.scheme_type {
                Some(ref scheme_type_box) => {
                    match scheme_type_box.scheme_type.value.as_ref() {
                        b"cenc" => Mp4ParseEncryptionSchemeType::Cenc,
                        b"cbcs" => Mp4ParseEncryptionSchemeType::Cbcs,
                        // We don't support other schemes, and shouldn't reach
                        // this case. Try to gracefully handle by treating as
                        // no encryption case.
                        _ => Mp4ParseEncryptionSchemeType::None,
                    }
                }
                None => Mp4ParseEncryptionSchemeType::None,
            };
            if let Some(ref tenc) = p.tenc {
                sample_info.protected_data.is_encrypted = tenc.is_encrypted;
                sample_info.protected_data.iv_size = tenc.iv_size;
                sample_info.protected_data.kid.set_data(&(tenc.kid));
                sample_info.protected_data.crypt_byte_block =
                    tenc.crypt_byte_block_count.unwrap_or(0);
                sample_info.protected_data.skip_byte_block =
                    tenc.skip_byte_block_count.unwrap_or(0);
                if let Some(ref iv_vec) = tenc.constant_iv {
                    if iv_vec.len() > u32::MAX as usize {
                        return Err(Mp4parseStatus::Invalid);
                    }
                    sample_info.protected_data.constant_iv.set_data(iv_vec);
                };
            }
        }
        video_sample_infos.push(sample_info)?;
    }

    parser
        .video_track_sample_descriptions
        .insert(track_index, video_sample_infos)?;
    match parser.video_track_sample_descriptions.get(&track_index) {
        Some(sample_info) => {
            if sample_info.len() > u32::MAX as usize {
                // Should never happen due to upper limits on number of sample
                // descriptions a track can have, but lets be safe.
                return Err(Mp4parseStatus::Invalid);
            }
            info.sample_info_count = sample_info.len() as u32;
            info.sample_info = sample_info.as_ptr();
        }
        None => return Err(Mp4parseStatus::Invalid), // Shouldn't happen, we just inserted the info!
    }
    Ok(())
}

/// Return a struct containing meta information read by previous
/// `mp4parse_avif_new()` call.
///
/// `color_track_id`and `alpha_track_id` will be 0 if has_sequence is false.
/// `alpha_track_id` will be 0 if no alpha aux track is present.
///
/// # Safety
///
/// This function is unsafe because it dereferences both the parser and
/// avif_info raw pointers passed into it. Callers should ensure the parser
/// pointer points to a valid `Mp4parseAvifParser`, and that the avif_info
/// pointer points to a valid `Mp4parseAvifInfo`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_avif_get_info(
    parser: *const Mp4parseAvifParser,
    avif_info: *mut Mp4parseAvifInfo,
) -> Mp4parseStatus {
    if parser.is_null() || avif_info.is_null() {
        return Mp4parseStatus::BadArg;
    }

    if let Ok(info) = mp4parse_avif_get_info_safe((*parser).context()) {
        *avif_info = info;
        Mp4parseStatus::Ok
    } else {
        Mp4parseStatus::Invalid
    }
}

fn mp4parse_avif_get_info_safe(context: &AvifContext) -> mp4parse::Result<Mp4parseAvifInfo> {
    let info = Mp4parseAvifInfo {
        premultiplied_alpha: context.premultiplied_alpha,
        major_brand: context.major_brand.value,
        unsupported_features_bitfield: context.unsupported_features.into_bitfield(),
        spatial_extents: context.spatial_extents_ptr()?,
        nclx_colour_information: context
            .nclx_colour_information_ptr()
            .unwrap_or(Ok(std::ptr::null()))?,
        icc_colour_information: Mp4parseByteData::with_data(
            context.icc_colour_information().unwrap_or(Ok(&[]))?,
        ),
        image_rotation: context.image_rotation()?,
        image_mirror: context.image_mirror_ptr()?,
        pixel_aspect_ratio: context.pixel_aspect_ratio_ptr()?,

        has_primary_item: context.primary_item_is_present(),
        primary_item_bit_depth: 0,
        has_alpha_item: context.alpha_item_is_present(),
        alpha_item_bit_depth: 0,

        has_sequence: false,
        loop_mode: Mp4parseAvifLoopMode::NoEdits,
        loop_count: 0,
        color_track_id: 0,
        color_track_bit_depth: 0,
        alpha_track_id: 0,
        alpha_track_bit_depth: 0,
    };

    fn get_bit_depth(data: &[u8]) -> u8 {
        if !data.is_empty() && data.iter().all(|v| *v == data[0]) {
            data[0]
        } else {
            0
        }
    }
    let primary_item_bit_depth =
        get_bit_depth(context.primary_item_bits_per_channel().unwrap_or(Ok(&[]))?);
    let alpha_item_bit_depth =
        get_bit_depth(context.alpha_item_bits_per_channel().unwrap_or(Ok(&[]))?);

    if let Some(sequence) = &context.sequence {
        // Tracks must have track_id and samples
        fn get_track<T>(tracks: &TryVec<Track>, pred: T) -> Option<&Track>
        where
            T: Fn(&Track) -> bool,
        {
            tracks.iter().find(|track| {
                if track.track_id.is_none() {
                    return false;
                }
                match &track.stsc {
                    Some(stsc) => {
                        if stsc.samples.is_empty() {
                            return false;
                        }
                        if !pred(track) {
                            return false;
                        }
                        stsc.samples.iter().any(|chunk| chunk.samples_per_chunk > 0)
                    }
                    _ => false,
                }
            })
        }

        // Color track will be the first track found
        let color_track = match get_track(&sequence.tracks, |_| true) {
            Some(v) => v,
            _ => return Ok(info),
        };

        // Alpha track will be the first track found with auxl.aux_for_track_id set to color_track's id
        let alpha_track = get_track(&sequence.tracks, |track| match &track.tref {
            Some(tref) => tref.has_auxl_reference(color_track.track_id.unwrap()),
            _ => false,
        });

        fn get_av1c(track: &Track) -> Option<&AV1ConfigBox> {
            if let Some(stsd) = &track.stsd {
                for entry in &stsd.descriptions {
                    if let SampleEntry::Video(video_entry) = entry {
                        if let VideoCodecSpecific::AV1Config(av1c) = &video_entry.codec_specific {
                            return Some(av1c);
                        }
                    }
                }
            }

            None
        }

        let color_track_id = color_track.track_id.unwrap();
        let color_track_bit_depth = match get_av1c(color_track) {
            Some(av1c) => av1c.bit_depth,
            _ => return Ok(info),
        };

        let (alpha_track_id, alpha_track_bit_depth) = match alpha_track {
            Some(track) => (
                track.track_id.unwrap(),
                match get_av1c(track) {
                    Some(av1c) => av1c.bit_depth,
                    _ => return Ok(info),
                },
            ),
            _ => (0, 0),
        };

        let (loop_mode, loop_count) = match color_track.tkhd.as_ref().map(|tkhd| tkhd.duration) {
            Some(movie_duration) if movie_duration == u64::MAX => {
                (Mp4parseAvifLoopMode::LoopInfinitely, 0)
            }
            Some(movie_duration) => match color_track.looped {
                Some(true) => match color_track.edited_duration.map(|v| v.0) {
                    Some(segment_duration) => {
                        match movie_duration.checked_div(segment_duration).and_then(|n| {
                            match movie_duration.checked_rem(segment_duration) {
                                Some(0) => Some(n.saturating_sub(1)),
                                Some(_) => Some(n),
                                None => None,
                            }
                        }) {
                            Some(n) => (Mp4parseAvifLoopMode::LoopByCount, n),
                            None => (Mp4parseAvifLoopMode::LoopInfinitely, 0),
                        }
                    }
                    None => (Mp4parseAvifLoopMode::NoEdits, 0),
                },
                Some(false) => (Mp4parseAvifLoopMode::LoopByCount, 0),
                None => (Mp4parseAvifLoopMode::NoEdits, 0),
            },
            None => (Mp4parseAvifLoopMode::LoopInfinitely, 0),
        };

        return Ok(Mp4parseAvifInfo {
            primary_item_bit_depth,
            alpha_item_bit_depth,
            has_sequence: true,
            loop_mode,
            loop_count,
            color_track_id,
            color_track_bit_depth,
            alpha_track_id,
            alpha_track_bit_depth,
            ..info
        });
    }

    Ok(info)
}

/// Return a pointer to the primary item parsed by previous `mp4parse_avif_new()` call.
///
/// # Safety
///
/// This function is unsafe because it dereferences both the parser and
/// avif_image raw pointers passed into it. Callers should ensure the parser
/// pointer points to a valid `Mp4parseAvifParser`, and that the avif_image
/// pointer points to a valid `Mp4parseAvifImage`. If there was not a previous
/// successful call to `mp4parse_avif_read()`, no guarantees are made as to
/// the state of `avif_image`. If `avif_image.alpha_image.coded_data` is set to
/// a positive `length` and non-null `data`, then the `avif_image` contains a
/// valid alpha channel data. Otherwise, the image is opaque.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_avif_get_image(
    parser: *const Mp4parseAvifParser,
    avif_image: *mut Mp4parseAvifImage,
) -> Mp4parseStatus {
    if parser.is_null() || avif_image.is_null() {
        return Mp4parseStatus::BadArg;
    }

    if let Ok(image) = mp4parse_avif_get_image_safe(&*parser) {
        *avif_image = image;
        Mp4parseStatus::Ok
    } else {
        Mp4parseStatus::Invalid
    }
}

pub fn mp4parse_avif_get_image_safe(
    parser: &Mp4parseAvifParser,
) -> mp4parse::Result<Mp4parseAvifImage> {
    let context = parser.context();
    Ok(Mp4parseAvifImage {
        primary_image: Mp4parseByteData::with_data(
            context.primary_item_coded_data().unwrap_or(&[]),
        ),
        alpha_image: Mp4parseByteData::with_data(context.alpha_item_coded_data().unwrap_or(&[])),
    })
}

/// Fill the supplied `Mp4parseByteData` with index information from `track`.
///
/// # Safety
///
/// This function is unsafe because it dereferences the the parser and indices
/// raw pointers passed to it. Callers should ensure the parser pointer points
/// to a valid `Mp4parseParser` and that the indices pointer points to a valid
/// `Mp4parseByteData`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_get_indice_table(
    parser: *mut Mp4parseParser,
    track_id: u32,
    indices: *mut Mp4parseByteData,
) -> Mp4parseStatus {
    if parser.is_null() {
        return Mp4parseStatus::BadArg;
    }

    // Initialize fields to default values to ensure all fields are always valid.
    *indices = Default::default();

    get_indice_table(
        &(*parser).context,
        &mut (*parser).sample_table,
        track_id,
        &mut *indices,
    )
    .into()
}

/// Fill the supplied `Mp4parseByteData` with index information from `track`.
///
/// # Safety
///
/// This function is unsafe because it dereferences both the parser and
/// indices raw pointers passed to it. Callers should ensure the parser
/// points to a valid `Mp4parseAvifParser` and indices points to a valid
/// `Mp4parseByteData`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_avif_get_indice_table(
    parser: *mut Mp4parseAvifParser,
    track_id: u32,
    indices: *mut Mp4parseByteData,
    timescale: *mut u64,
) -> Mp4parseStatus {
    if parser.is_null() {
        return Mp4parseStatus::BadArg;
    }

    if indices.is_null() {
        return Mp4parseStatus::BadArg;
    }

    if timescale.is_null() {
        return Mp4parseStatus::BadArg;
    }

    // Initialize fields to default values to ensure all fields are always valid.
    *indices = Default::default();

    if let Some(sequence) = &(*parser).context.sequence {
        // Use the top level timescale, and the track timescale if present.
        let mut found_timescale = false;
        if let Some(context_timescale) = sequence.timescale {
            *timescale = context_timescale.0;
            found_timescale = true;
        }
        let maybe_track_timescale = match sequence
            .tracks
            .iter()
            .find(|track| track.track_id == Some(track_id))
        {
            Some(track) => track.timescale,
            _ => None,
        };
        if let Some(track_timescale) = maybe_track_timescale {
            found_timescale = true;
            *timescale = track_timescale.0;
        }
        if !found_timescale {
            return Mp4parseStatus::Invalid;
        }
        return get_indice_table(
            sequence,
            &mut (*parser).sample_table,
            track_id,
            &mut *indices,
        )
        .into();
    }

    Mp4parseStatus::BadArg
}

fn get_indice_table(
    context: &MediaContext,
    sample_table_cache: &mut TryHashMap<u32, TryVec<Indice>>,
    track_id: u32,
    indices: &mut Mp4parseByteData,
) -> Result<(), Mp4parseStatus> {
    let tracks = &context.tracks;
    let track = match tracks.iter().find(|track| track.track_id == Some(track_id)) {
        Some(t) => t,
        _ => return Err(Mp4parseStatus::Invalid),
    };

    if let Some(v) = sample_table_cache.get(&track_id) {
        indices.set_indices(v);
        return Ok(());
    }

    let media_time = match &track.media_time {
        &Some(t) => i64::try_from(t.0).ok().map(Into::into),
        _ => None,
    };

    let empty_duration: Option<CheckedInteger<_>> = match &track.empty_duration {
        &Some(e) => i64::try_from(e.0).ok().map(Into::into),
        _ => None,
    };

    // Find the track start offset time from 'elst'.
    // 'media_time' maps start time onward, 'empty_duration' adds time offset
    // before first frame is displayed.
    let offset_time = match (empty_duration, media_time) {
        (Some(e), Some(m)) => (e - m).ok_or(Err(Mp4parseStatus::Invalid))?,
        (Some(e), None) => e,
        (None, Some(m)) => m,
        _ => 0.into(),
    };

    if let Some(v) = create_sample_table(track, offset_time) {
        indices.set_indices(&v);
        sample_table_cache.insert(track_id, v)?;
        return Ok(());
    }

    Err(Mp4parseStatus::Invalid)
}

/// Fill the supplied `Mp4parseFragmentInfo` with metadata from fragmented file.
///
/// # Safety
///
/// This function is unsafe because it dereferences the the parser and
/// info raw pointers passed to it. Callers should ensure the parser
/// pointer points to a valid `Mp4parseParser` and that the info pointer points
/// to a valid `Mp4parseFragmentInfo`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_get_fragment_info(
    parser: *mut Mp4parseParser,
    info: *mut Mp4parseFragmentInfo,
) -> Mp4parseStatus {
    if parser.is_null() || info.is_null() {
        return Mp4parseStatus::BadArg;
    }

    // Initialize fields to default values to ensure all fields are always valid.
    *info = Default::default();

    let context = (*parser).context();
    let info: &mut Mp4parseFragmentInfo = &mut *info;

    info.fragment_duration = 0;

    let duration = match context.mvex {
        Some(ref mvex) => mvex.fragment_duration,
        None => return Mp4parseStatus::Invalid,
    };

    if let (Some(time), Some(scale)) = (duration, context.timescale) {
        info.fragment_duration = time.0;
        info.time_scale = scale.0;
        return Mp4parseStatus::Ok;
    };
    Mp4parseStatus::Invalid
}

/// Determine if an mp4 file is fragmented. A fragmented file needs mvex table
/// and contains no data in stts, stsc, and stco boxes.
///
/// # Safety
///
/// This function is unsafe because it dereferences the the parser and
/// fragmented raw pointers passed to it. Callers should ensure the parser
/// pointer points to a valid `Mp4parseParser` and that the fragmented pointer
/// points to an appropriate memory location to have a `u8` written to.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_is_fragmented(
    parser: *mut Mp4parseParser,
    track_id: u32,
    fragmented: *mut u8,
) -> Mp4parseStatus {
    if parser.is_null() {
        return Mp4parseStatus::BadArg;
    }

    let context = (*parser).context_mut();
    let tracks = &context.tracks;
    (*fragmented) = false as u8;

    if context.mvex.is_none() {
        return Mp4parseStatus::Ok;
    }

    // check sample tables.
    let mut iter = tracks.iter();
    iter.find(|track| track.track_id == Some(track_id))
        .map_or(Mp4parseStatus::BadArg, |track| {
            match (&track.stsc, &track.stco, &track.stts) {
                (Some(stsc), Some(stco), Some(stts))
                    if stsc.samples.is_empty()
                        && stco.offsets.is_empty()
                        && stts.samples.is_empty() =>
                {
                    (*fragmented) = true as u8
                }
                _ => {}
            };
            Mp4parseStatus::Ok
        })
}

/// Get 'pssh' system id and 'pssh' box content for eme playback.
///
/// The data format of the `info` struct passed to gecko is:
///
/// - system id (16 byte uuid)
/// - pssh box size (32-bit native endian)
/// - pssh box content (including header)
///
/// # Safety
///
/// This function is unsafe because it dereferences the the parser and
/// info raw pointers passed to it. Callers should ensure the parser
/// pointer points to a valid `Mp4parseParser` and that the fragmented pointer
/// points to a valid `Mp4parsePsshInfo`.
#[no_mangle]
pub unsafe extern "C" fn mp4parse_get_pssh_info(
    parser: *mut Mp4parseParser,
    info: *mut Mp4parsePsshInfo,
) -> Mp4parseStatus {
    if parser.is_null() || info.is_null() {
        return Mp4parseStatus::BadArg;
    }

    // Initialize fields to default values to ensure all fields are always valid.
    *info = Default::default();

    get_pssh_info(&mut *parser, &mut *info).into()
}

fn get_pssh_info(
    parser: &mut Mp4parseParser,
    info: &mut Mp4parsePsshInfo,
) -> Result<(), Mp4parseStatus> {
    let Mp4parseParser {
        context, pssh_data, ..
    } = parser;

    pssh_data.clear();
    for pssh in &context.psshs {
        let content_len = pssh
            .box_content
            .len()
            .try_into()
            .map_err(|_| Mp4parseStatus::Invalid)?;
        let mut data_len = TryVec::new();
        data_len.write_u32::<byteorder::NativeEndian>(content_len)?;
        pssh_data.extend_from_slice(pssh.system_id.as_slice())?;
        pssh_data.extend_from_slice(data_len.as_slice())?;
        pssh_data.extend_from_slice(pssh.box_content.as_slice())?;
    }

    info.data.set_data(pssh_data);

    Ok(())
}

#[cfg(test)]
extern "C" fn error_read(_: *mut u8, _: usize, _: *mut std::os::raw::c_void) -> isize {
    -1
}

#[cfg(test)]
extern "C" fn valid_read(buf: *mut u8, size: usize, userdata: *mut std::os::raw::c_void) -> isize {
    let input: &mut std::fs::File = unsafe { &mut *(userdata as *mut _) };

    let buf = unsafe { std::slice::from_raw_parts_mut(buf, size) };
    match input.read(buf) {
        Ok(n) => n as isize,
        Err(_) => -1,
    }
}

#[test]
fn get_track_count_null_parser() {
    unsafe {
        let mut count: u32 = 0;
        let rv = mp4parse_get_track_count(std::ptr::null(), std::ptr::null_mut());
        assert_eq!(rv, Mp4parseStatus::BadArg);
        let rv = mp4parse_get_track_count(std::ptr::null(), &mut count);
        assert_eq!(rv, Mp4parseStatus::BadArg);
    }
}

#[test]
fn arg_validation() {
    unsafe {
        let rv = mp4parse_new(std::ptr::null(), std::ptr::null_mut());
        assert_eq!(rv, Mp4parseStatus::BadArg);

        // Passing a null Mp4parseIo is an error.
        let mut parser = std::ptr::null_mut();
        let rv = mp4parse_new(std::ptr::null(), &mut parser);
        assert_eq!(rv, Mp4parseStatus::BadArg);
        assert!(parser.is_null());

        let null_mut: *mut std::os::raw::c_void = std::ptr::null_mut();

        // Passing an Mp4parseIo with null members is an error.
        let io = Mp4parseIo {
            read: None,
            userdata: null_mut,
        };
        let mut parser = std::ptr::null_mut();
        let rv = mp4parse_new(&io, &mut parser);
        assert_eq!(rv, Mp4parseStatus::BadArg);
        assert!(parser.is_null());

        let mut dummy_value = 42;
        let io = Mp4parseIo {
            read: None,
            userdata: &mut dummy_value as *mut _ as *mut std::os::raw::c_void,
        };
        let mut parser = std::ptr::null_mut();
        let rv = mp4parse_new(&io, &mut parser);
        assert_eq!(rv, Mp4parseStatus::BadArg);
        assert!(parser.is_null());

        let mut dummy_info = Mp4parseTrackInfo {
            track_type: Mp4parseTrackType::Video,
            ..Default::default()
        };
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_info(std::ptr::null_mut(), 0, &mut dummy_info)
        );

        let mut dummy_video = Default::default();
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_video_info(std::ptr::null_mut(), 0, &mut dummy_video)
        );

        let mut dummy_audio = Default::default();
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_audio_info(std::ptr::null_mut(), 0, &mut dummy_audio)
        );
    }
}

#[test]
fn parser_input_must_be_null() {
    let mut dummy_value = 42;
    let io = Mp4parseIo {
        read: Some(error_read),
        userdata: &mut dummy_value as *mut _ as *mut std::os::raw::c_void,
    };
    let mut parser = 0xDEAD_BEEF as *mut _;
    let rv = unsafe { mp4parse_new(&io, &mut parser) };
    assert_eq!(rv, Mp4parseStatus::BadArg);
}

#[test]
fn arg_validation_with_parser() {
    unsafe {
        let mut dummy_value = 42;
        let io = Mp4parseIo {
            read: Some(error_read),
            userdata: &mut dummy_value as *mut _ as *mut std::os::raw::c_void,
        };
        let mut parser = std::ptr::null_mut();
        let rv = mp4parse_new(&io, &mut parser);
        assert_eq!(rv, Mp4parseStatus::Io);
        assert!(parser.is_null());

        // Null info pointers are an error.
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_info(parser, 0, std::ptr::null_mut())
        );
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_video_info(parser, 0, std::ptr::null_mut())
        );
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_audio_info(parser, 0, std::ptr::null_mut())
        );

        let mut dummy_info = Mp4parseTrackInfo {
            track_type: Mp4parseTrackType::Video,
            ..Default::default()
        };
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_info(parser, 0, &mut dummy_info)
        );

        let mut dummy_video = Default::default();
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_video_info(parser, 0, &mut dummy_video)
        );

        let mut dummy_audio = Default::default();
        assert_eq!(
            Mp4parseStatus::BadArg,
            mp4parse_get_track_audio_info(parser, 0, &mut dummy_audio)
        );
    }
}

#[cfg(test)]
fn parse_minimal_mp4() -> *mut Mp4parseParser {
    let mut file = std::fs::File::open("../mp4parse/tests/minimal.mp4").unwrap();
    let io = Mp4parseIo {
        read: Some(valid_read),
        userdata: &mut file as *mut _ as *mut std::os::raw::c_void,
    };
    let mut parser = std::ptr::null_mut();
    let rv = unsafe { mp4parse_new(&io, &mut parser) };
    assert_eq!(Mp4parseStatus::Ok, rv);
    parser
}

#[test]
fn minimal_mp4_parse_ok() {
    let parser = parse_minimal_mp4();

    assert!(!parser.is_null());

    unsafe {
        mp4parse_free(parser);
    }
}

#[test]
fn minimal_mp4_get_track_cout() {
    let parser = parse_minimal_mp4();

    let mut count: u32 = 0;
    assert_eq!(Mp4parseStatus::Ok, unsafe {
        mp4parse_get_track_count(parser, &mut count)
    });
    assert_eq!(2, count);

    unsafe {
        mp4parse_free(parser);
    }
}

#[test]
fn minimal_mp4_get_track_info() {
    let parser = parse_minimal_mp4();

    let mut info = Mp4parseTrackInfo {
        track_type: Mp4parseTrackType::Video,
        ..Default::default()
    };
    assert_eq!(Mp4parseStatus::Ok, unsafe {
        mp4parse_get_track_info(parser, 0, &mut info)
    });
    assert_eq!(info.track_type, Mp4parseTrackType::Video);
    assert_eq!(info.track_id, 1);
    assert_eq!(info.duration, 512);
    assert_eq!(info.media_time, 0);

    assert_eq!(Mp4parseStatus::Ok, unsafe {
        mp4parse_get_track_info(parser, 1, &mut info)
    });
    assert_eq!(info.track_type, Mp4parseTrackType::Audio);
    assert_eq!(info.track_id, 2);
    assert_eq!(info.duration, 2944);
    assert_eq!(info.media_time, 1024);

    unsafe {
        mp4parse_free(parser);
    }
}

#[test]
fn minimal_mp4_get_track_video_info() {
    let parser = parse_minimal_mp4();

    let mut video = Mp4parseTrackVideoInfo::default();
    assert_eq!(Mp4parseStatus::Ok, unsafe {
        mp4parse_get_track_video_info(parser, 0, &mut video)
    });
    assert_eq!(video.display_width, 320);
    assert_eq!(video.display_height, 240);
    assert_eq!(video.sample_info_count, 1);

    unsafe {
        assert_eq!((*video.sample_info).image_width, 320);
        assert_eq!((*video.sample_info).image_height, 240);
    }

    unsafe {
        mp4parse_free(parser);
    }
}

#[test]
fn minimal_mp4_get_track_audio_info() {
    let parser = parse_minimal_mp4();

    let mut audio = Mp4parseTrackAudioInfo::default();
    assert_eq!(Mp4parseStatus::Ok, unsafe {
        mp4parse_get_track_audio_info(parser, 1, &mut audio)
    });
    assert_eq!(audio.sample_info_count, 1);

    unsafe {
        assert_eq!((*audio.sample_info).channels, 1);
        assert_eq!((*audio.sample_info).bit_depth, 16);
        assert_eq!((*audio.sample_info).sample_rate, 48000);
    }

    unsafe {
        mp4parse_free(parser);
    }
}

#[test]
fn minimal_mp4_get_track_info_invalid_track_number() {
    let parser = parse_minimal_mp4();

    let mut info = Mp4parseTrackInfo {
        track_type: Mp4parseTrackType::Video,
        ..Default::default()
    };
    assert_eq!(Mp4parseStatus::BadArg, unsafe {
        mp4parse_get_track_info(parser, 3, &mut info)
    });
    assert_eq!(info.track_type, Mp4parseTrackType::Video);
    assert_eq!(info.track_id, 0);
    assert_eq!(info.duration, 0);
    assert_eq!(info.media_time, 0);

    let mut video = Mp4parseTrackVideoInfo::default();
    assert_eq!(Mp4parseStatus::BadArg, unsafe {
        mp4parse_get_track_video_info(parser, 3, &mut video)
    });
    assert_eq!(video.display_width, 0);
    assert_eq!(video.display_height, 0);
    assert_eq!(video.sample_info_count, 0);

    let mut audio = Default::default();
    assert_eq!(Mp4parseStatus::BadArg, unsafe {
        mp4parse_get_track_audio_info(parser, 3, &mut audio)
    });
    assert_eq!(audio.sample_info_count, 0);

    unsafe {
        mp4parse_free(parser);
    }
}

#[test]
fn parse_no_timescale() {
    let mut file = std::fs::File::open("tests/no_timescale.mp4").expect("Unknown file");
    let io = Mp4parseIo {
        read: Some(valid_read),
        userdata: &mut file as *mut _ as *mut std::os::raw::c_void,
    };

    unsafe {
        let mut parser = std::ptr::null_mut();
        let mut rv = mp4parse_new(&io, &mut parser);
        assert_eq!(rv, Mp4parseStatus::Ok);
        assert!(!parser.is_null());

        // The file has a video track, but the track has a timescale of 0, so.
        let mut track_info = Mp4parseTrackInfo::default();
        rv = mp4parse_get_track_info(parser, 0, &mut track_info);
        assert_eq!(rv, Mp4parseStatus::Invalid);
    };
}
